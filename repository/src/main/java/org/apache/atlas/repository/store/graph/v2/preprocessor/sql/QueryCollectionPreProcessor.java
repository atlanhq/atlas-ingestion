/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.atlas.repository.store.graph.v2.preprocessor.sql;


import org.apache.atlas.RequestContext;
import org.apache.atlas.authorize.AtlasAuthorizationUtils;
import org.apache.atlas.discovery.EntityDiscoveryService;
import org.apache.atlas.exception.AtlasBaseException;
import org.apache.atlas.featureflag.FeatureFlagStore;
import org.apache.atlas.keycloak.client.KeycloakClient;
import org.apache.atlas.model.discovery.AtlasSearchResult;
import org.apache.atlas.model.discovery.IndexSearchParams;
import org.apache.atlas.model.instance.AtlasEntity;
import org.apache.atlas.model.instance.AtlasEntityHeader;
import org.apache.atlas.model.instance.AtlasStruct;
import org.apache.atlas.model.instance.EntityMutationResponse;
import org.apache.atlas.model.instance.EntityMutations;
import org.apache.atlas.repository.graphdb.AtlasVertex;
import org.apache.atlas.repository.store.graph.AtlasEntityStore;
import org.apache.atlas.repository.store.graph.v2.AtlasEntityStream;
import org.apache.atlas.repository.store.graph.v2.EntityGraphRetriever;
import org.apache.atlas.repository.store.graph.v2.EntityMutationContext;
import org.apache.atlas.repository.store.graph.v2.EntityStream;
import org.apache.atlas.repository.store.graph.v2.preprocessor.PreProcessor;
import org.apache.atlas.repository.store.users.KeycloakStore;
import org.apache.atlas.transformer.PreProcessorPoliciesTransformer;
import org.apache.atlas.type.AtlasTypeRegistry;
import org.apache.atlas.utils.AtlasPerfMetrics;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.keycloak.admin.client.resource.RoleResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.apache.atlas.authorize.AtlasAuthorizerFactory.ATLAS_AUTHORIZER_IMPL;
import static org.apache.atlas.authorize.AtlasAuthorizerFactory.CURRENT_AUTHORIZER_IMPL;
import static org.apache.atlas.repository.Constants.ATTR_ADMIN_GROUPS;
import static org.apache.atlas.repository.Constants.ATTR_ADMIN_ROLES;
import static org.apache.atlas.repository.Constants.ATTR_ADMIN_USERS;
import static org.apache.atlas.repository.Constants.ATTR_VIEWER_GROUPS;
import static org.apache.atlas.repository.Constants.ATTR_VIEWER_USERS;
import static org.apache.atlas.repository.Constants.CREATED_BY_KEY;
import static org.apache.atlas.repository.Constants.POLICY_ENTITY_TYPE;
import static org.apache.atlas.repository.Constants.QUALIFIED_NAME;
import static org.apache.atlas.repository.store.graph.v2.preprocessor.PreProcessorUtils.PREFIX_QUERY_QN;
import static org.apache.atlas.repository.store.graph.v2.preprocessor.PreProcessorUtils.getUUID;
import static org.apache.atlas.repository.util.AccessControlUtils.checkAccessControlFeatureStatus;
import static org.apache.atlas.repository.util.AccessControlUtils.checkAccessControlFeatureStatusForUpdate;
import static org.apache.atlas.repository.util.AtlasEntityUtils.mapOf;

public class QueryCollectionPreProcessor implements PreProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(QueryCollectionPreProcessor.class);

    private static final String qualifiedNameFormat = PREFIX_QUERY_QN + "%s/%s";
    private static final String COLL_ADMIN_ROLE_PATTERN = "collection_admins_%s";
    private static final String COLL_VIEWER_ROLE_PATTERN = "collection_viewer_%s";

    private final AtlasTypeRegistry typeRegistry;
    private final EntityGraphRetriever entityRetriever;
    private AtlasEntityStore entityStore;
    private EntityDiscoveryService discovery;
    private PreProcessorPoliciesTransformer transformer;
    private FeatureFlagStore featureFlagStore;
    private KeycloakStore keycloakStore;

    public QueryCollectionPreProcessor(AtlasTypeRegistry typeRegistry,
                                       EntityDiscoveryService discovery,
                                       EntityGraphRetriever entityRetriever,
                                       FeatureFlagStore featureFlagStore,
                                       AtlasEntityStore entityStore) {
        this.entityRetriever = entityRetriever;
        this.typeRegistry = typeRegistry;
        this.entityStore = entityStore;
        this.featureFlagStore = featureFlagStore;
        this.discovery = discovery;

        transformer = new PreProcessorPoliciesTransformer();
        keycloakStore = new KeycloakStore();
    }

    @Override
    public void processAttributes(AtlasStruct entityStruct, EntityMutationContext context,
                                  EntityMutations.EntityOperation operation) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("QueryCollectionPreProcessor.processAttributes: pre processing {}, {}", entityStruct.getAttribute(QUALIFIED_NAME), operation);
        }

        AtlasEntity entity = (AtlasEntity) entityStruct;
        AtlasVertex vertex = context.getVertex(entity.getGuid());

        switch (operation) {
            case CREATE:
                processCreate(entity);
                break;
            case UPDATE:
                processUpdate(entity, vertex);
                break;
        }
    }

    private void processCreate(AtlasStruct entity) throws AtlasBaseException {
        AtlasPerfMetrics.MetricRecorder metricRecorder = RequestContext.get().startMetricRecord("processCreateCollection");

        try {
            checkAccessControlFeatureStatus(featureFlagStore);


            entity.setAttribute(QUALIFIED_NAME, createQualifiedName());

            AtlasEntity collection = (AtlasEntity) entity;

            if (ATLAS_AUTHORIZER_IMPL.equalsIgnoreCase(CURRENT_AUTHORIZER_IMPL)) {

                createCollectionAdminRole(collection);
                createCollectionViewerRole(collection);

                //create bootstrap policies
                AtlasEntity.AtlasEntitiesWithExtInfo policies = transformer.transform(collection);

                try {
                    RequestContext.get().setPoliciesBootstrappingInProgress(true);
                    EntityStream entityStream = new AtlasEntityStream(policies);
                    entityStore.createOrUpdate(entityStream, false);
                    LOG.info("Created bootstrap policies for collection {}", entity.getAttribute(QUALIFIED_NAME));
                } finally {
                    RequestContext.get().setPoliciesBootstrappingInProgress(false);
                }
            }
        } finally {
            RequestContext.get().endMetricRecord(metricRecorder);
        }
    }

    private void processUpdate(AtlasStruct entity, AtlasVertex vertex) throws AtlasBaseException {
        String vertexQnName = vertex.getProperty(QUALIFIED_NAME, String.class);

        checkAccessControlFeatureStatusForUpdate(featureFlagStore, entity, vertex);

        if (ATLAS_AUTHORIZER_IMPL.equalsIgnoreCase(CURRENT_AUTHORIZER_IMPL)) {
            AtlasEntity collection = (AtlasEntity) entity;
            AtlasEntity existingCollEntity = entityRetriever.toAtlasEntity(vertex);


            updateCollectionAdminRole(collection, existingCollEntity, vertex);
            updateCollectionViewerRole(collection, existingCollEntity);
        }

        entity.setAttribute(QUALIFIED_NAME, vertexQnName);
    }

    @Override
    public void processDelete(AtlasVertex vertex) throws AtlasBaseException {
        AtlasPerfMetrics.MetricRecorder metricRecorder = RequestContext.get().startMetricRecord("processDeleteCollection");
        checkAccessControlFeatureStatus(featureFlagStore);

        try {
            AtlasEntity.AtlasEntityWithExtInfo entityWithExtInfo = entityRetriever.toAtlasEntityWithExtInfo(vertex);
            AtlasEntity collection = entityWithExtInfo.getEntity();

            if (!AtlasEntity.Status.ACTIVE.equals(collection.getStatus())) {
                throw new AtlasBaseException("Collection is already deleted/purged");
            }

            if (ATLAS_AUTHORIZER_IMPL.equalsIgnoreCase(CURRENT_AUTHORIZER_IMPL)) {
                //delete collection policies
                List<AtlasEntityHeader> policies = getCollectionPolicies(collection.getGuid());
                EntityMutationResponse response = entityStore.deleteByIds(policies.stream().map(x -> x.getGuid()).collect(Collectors.toList()));

                //delete collection roles
                String adminRoleName = String.format(COLL_ADMIN_ROLE_PATTERN, collection.getGuid());
                String viewerRoleName = String.format(COLL_VIEWER_ROLE_PATTERN, collection.getGuid());

                keycloakStore.removeRoleByName(adminRoleName);
                keycloakStore.removeRoleByName(viewerRoleName);
            }
        } finally {
            RequestContext.get().endMetricRecord(metricRecorder);
        }
    }

    private static String createQualifiedName() {
        return String.format(qualifiedNameFormat, AtlasAuthorizationUtils.getCurrentUserName(), getUUID());
    }

    private RoleRepresentation createCollectionAdminRole(AtlasEntity collection) throws AtlasBaseException {
        //create Admin role
        List<String> adminUsers = (List<String>) collection.getAttribute(ATTR_ADMIN_USERS);
        List<String> adminGroups = (List<String>) collection.getAttribute(ATTR_ADMIN_GROUPS);
        List<String> adminRoles = (List<String>) collection.getAttribute(ATTR_ADMIN_ROLES);

        if (adminUsers == null) {
            adminUsers = new ArrayList<>();
        }
        String creatorUser = RequestContext.get().getUser();
        if (StringUtils.isNotEmpty(creatorUser)) {
            adminUsers.add(creatorUser);
        }
        collection.setAttribute(ATTR_ADMIN_USERS, adminUsers);

        String adminRoleName = String.format(COLL_ADMIN_ROLE_PATTERN, collection.getGuid());
        return keycloakStore.createRoleForConnection(adminRoleName, true, adminUsers, adminGroups, adminRoles);
    }

    private RoleRepresentation createCollectionViewerRole(AtlasEntity collection) throws AtlasBaseException {
        //create viewers role
        String viewerRoleName = String.format(COLL_VIEWER_ROLE_PATTERN, collection.getGuid());
        List<String> viewerUsers = (List<String>) collection.getAttribute(ATTR_VIEWER_USERS);
        List<String> viewerGroups = (List<String>) collection.getAttribute(ATTR_VIEWER_GROUPS);

        return keycloakStore.createRoleForConnection(viewerRoleName, true, viewerUsers, viewerGroups, null);
    }

    private void updateCollectionAdminRole(AtlasEntity collection, AtlasEntity existingCollEntity, AtlasVertex vertex) throws AtlasBaseException {
        String adminRoleName = String.format(COLL_ADMIN_ROLE_PATTERN, collection.getGuid());

        RoleResource rolesResource = KeycloakClient.getKeycloakClient().getRealm().roles().get(adminRoleName);
        RoleRepresentation representation = rolesResource.toRepresentation();
        String creatorUser = vertex.getProperty(CREATED_BY_KEY, String.class);

        if (collection.hasAttribute(ATTR_ADMIN_USERS)) {
            List<String> newAdminUsers = (List<String>) collection.getAttribute(ATTR_ADMIN_USERS);
            List<String> currentAdminUsers = (List<String>) existingCollEntity.getAttribute(ATTR_ADMIN_USERS);

            if (CollectionUtils.isNotEmpty(newAdminUsers) || CollectionUtils.isNotEmpty(currentAdminUsers)) {
                if (StringUtils.isNotEmpty(creatorUser) && !newAdminUsers.contains(creatorUser)) {
                    newAdminUsers.add(creatorUser);
                }
                collection.setAttribute(ATTR_ADMIN_USERS, newAdminUsers);
                keycloakStore.updateRoleUsers(adminRoleName, currentAdminUsers, newAdminUsers, representation);
            }
        }

        if (collection.hasAttribute(ATTR_ADMIN_GROUPS)) {
            List<String> newAdminGroups = (List<String>) collection.getAttribute(ATTR_ADMIN_GROUPS);
            List<String> currentAdminGroups =(List<String>)  existingCollEntity.getAttribute(ATTR_ADMIN_GROUPS);

            if (CollectionUtils.isNotEmpty(newAdminGroups) || CollectionUtils.isNotEmpty(currentAdminGroups)) {
                keycloakStore.updateRoleGroups(adminRoleName, currentAdminGroups, newAdminGroups, representation);
            }
        }

        if (collection.hasAttribute(ATTR_ADMIN_ROLES)) {
            List<String> newAdminRoles = (List<String>) collection.getAttribute(ATTR_ADMIN_ROLES);
            List<String> currentAdminRoles = (List<String>) existingCollEntity.getAttribute(ATTR_ADMIN_ROLES);

            if (CollectionUtils.isNotEmpty(newAdminRoles) || CollectionUtils.isNotEmpty(currentAdminRoles)) {
                keycloakStore.updateRoleRoles(adminRoleName, currentAdminRoles, newAdminRoles, rolesResource, representation);
            }
        }
    }

    private void updateCollectionViewerRole(AtlasEntity collection, AtlasEntity existingCollEntity) throws AtlasBaseException {
        String viewerRoleName = String.format(COLL_VIEWER_ROLE_PATTERN, collection.getGuid());

        RoleResource rolesResource = KeycloakClient.getKeycloakClient().getRealm().roles().get(viewerRoleName);
        RoleRepresentation representation = rolesResource.toRepresentation();

        if (collection.hasAttribute(ATTR_VIEWER_USERS)) {
            List<String> newViewerUsers = (List<String>) collection.getAttribute(ATTR_VIEWER_USERS);
            List<String> currentViewerUsers = (List<String>) existingCollEntity.getAttribute(ATTR_VIEWER_USERS);

            if (CollectionUtils.isNotEmpty(newViewerUsers) || CollectionUtils.isNotEmpty(currentViewerUsers)) {
                keycloakStore.updateRoleUsers(viewerRoleName, currentViewerUsers, newViewerUsers, representation);
            }
        }

        if (collection.hasAttribute(ATTR_VIEWER_GROUPS)) {
            List<String> newViewerGroups = (List<String>) collection.getAttribute(ATTR_VIEWER_GROUPS);
            List<String> currentViewerGroups =(List<String>)  existingCollEntity.getAttribute(ATTR_VIEWER_GROUPS);

            if (CollectionUtils.isNotEmpty(newViewerGroups) || CollectionUtils.isNotEmpty(currentViewerGroups)) {
                keycloakStore.updateRoleGroups(viewerRoleName, currentViewerGroups, newViewerGroups, representation);
            }
        }
    }

    private List<AtlasEntityHeader> getCollectionPolicies(String guid) throws AtlasBaseException {
        List<AtlasEntityHeader> ret = new ArrayList<>();

        IndexSearchParams indexSearchParams = new IndexSearchParams();
        Map<String, Object> dsl = new HashMap<>();

        List mustClauseList = new ArrayList();
        mustClauseList.add(mapOf("term", mapOf("__typeName.keyword", POLICY_ENTITY_TYPE)));
        mustClauseList.add(mapOf("term", mapOf("__state", "ACTIVE")));


        mustClauseList.add(mapOf("wildcard", mapOf(QUALIFIED_NAME, guid + "/*")));

        dsl.put("query", mapOf("bool", mapOf("must", mustClauseList)));

        indexSearchParams.setDsl(dsl);

        AtlasSearchResult result = discovery.directIndexSearch(indexSearchParams);
        if (result != null) {
            ret = result.getEntities();
        }

        return ret;
    }
}
