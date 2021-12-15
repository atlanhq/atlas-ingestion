/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.atlas.repository.audit;

import com.google.common.annotations.VisibleForTesting;
import org.apache.atlas.ApplicationProperties;
import org.apache.atlas.AtlasException;
import org.apache.atlas.EntityAuditEvent;
import org.apache.atlas.annotation.ConditionalOnAtlasProperty;
import org.apache.atlas.exception.AtlasBaseException;
import org.apache.atlas.model.audit.EntityAuditEventV2;
import org.apache.atlas.model.audit.EntityAuditSearchResult;
import org.apache.atlas.type.AtlasType;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.lang.NotImplementedException;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.MessageFormat;
import java.util.*;

import javax.inject.Singleton;

/**
 * This class provides cassandra support as the backend for audit storage support.
 */
@Singleton
@Component
@ConditionalOnAtlasProperty(property = "atlas.EntityAuditRepositorySearch.impl")
public class ESBasedAuditRepository extends AbstractStorageBasedAuditRepository {
    private static final Logger LOG = LoggerFactory.getLogger(ESBasedAuditRepository.class);
    public static final String INDEX_BACKEND_CONF = "atlas.graph.index.search.hostname";
    public static final String INDEX_NAME = "entity_audits";
    private static final String ENTITYID = "entityid";
    private static final String CREATED = "created";
    private static final String ACTION = "action";
    private static final String USER = "user";
    private static final String DETAIL = "detail";
    private static final String ENTITY = "entity";

    private RestClient lowLevelClient;

    @Override
    public void putEventsV1(List<EntityAuditEvent> events) throws AtlasException {

    }

    @Override
    public List<EntityAuditEvent> listEventsV1(String entityId, String startKey, short n) throws AtlasException {
        return null;
    }

    @Override
    public void putEventsV2(List<EntityAuditEventV2> events) throws AtlasBaseException {
        try {
            if (events != null && events.size() > 0) {
                String entityPayloadTemplate = "'{'\"entityid\":\"{0}\",\"created\":{1},\"action\":\"{2}\",\"detail\":{3},\"user\":\"{4}\"'}'";
                String bulkMetadata = String.format("{ \"index\" : { \"_index\" : \"%s\" } }%n", INDEX_NAME);
                StringBuilder bulkRequestBody = new StringBuilder();
                for (EntityAuditEventV2 event : events) {
                    String created = String.format("%s", event.getTimestamp());
                    String details;
                    if (event.getAction().equals(EntityAuditEventV2.EntityAuditActionV2.ENTITY_DELETE) || event.getAction().equals(EntityAuditEventV2.EntityAuditActionV2.ENTITY_PURGE)) {
                        details = "{}";
                    } else {
                        String auditDetailPrefix = EntityAuditListenerV2.getV2AuditPrefix(event.getAction());
                        details = event.getDetails().substring(auditDetailPrefix.length());
                    }
                    String bulkItem = MessageFormat.format(entityPayloadTemplate, event.getEntityId(), created, event.getAction(), details, event.getUser());
                    bulkRequestBody.append(bulkMetadata);
                    bulkRequestBody.append(bulkItem);
                    bulkRequestBody.append("\n");
                }
                String endpoint = INDEX_NAME + "/_bulk";
                HttpEntity entity = new NStringEntity(bulkRequestBody.toString(), ContentType.APPLICATION_JSON);
                Request request = new Request("POST", endpoint);
                request.setEntity(entity);
                Response response = lowLevelClient.performRequest(request);
                int statusCode = response.getStatusLine().getStatusCode();;
                if (statusCode != 200) {
                    throw new AtlasException("Unable to push entity audits to ES");
                }
                String responseString = EntityUtils.toString(response.getEntity());
                Map<String, Object> responseMap = AtlasType.fromJson(responseString, Map.class);
                if ((boolean) responseMap.get("errors")) {
                    throw new AtlasException("Unable to push entity audits to ES (errors: true returned by es)");
                }
            }
        } catch (Exception e) {
            throw new AtlasBaseException(e);
        }
    }

    @Override
    public List<EntityAuditEventV2> listEventsV2(String entityId, EntityAuditEventV2.EntityAuditActionV2 auditAction, String startKey, short maxResultCount) throws AtlasBaseException {
        return null;
    }

    @Override
    public List<EntityAuditEventV2> listEventsV2(String entityId, EntityAuditEventV2.EntityAuditActionV2 auditAction, String sortByColumn, boolean sortOrderDesc, int offset, short limit) throws AtlasBaseException {
        return null;
    }

    @Override
    public EntityAuditSearchResult searchEvents(String queryString) throws AtlasBaseException {
        try {
            String response = performSearchOnIndex(queryString);
            return getResultFromResponse(response);
        } catch (IOException e) {
            throw new AtlasBaseException(e);
        }
    }

    private EntityAuditSearchResult getResultFromResponse(String responseString) {
        List<EntityAuditEventV2> entityAudits = new ArrayList<>();
        EntityAuditSearchResult searchResult = new EntityAuditSearchResult();
        Map<String, Object> responseMap = AtlasType.fromJson(responseString, Map.class);
        Map<String, Object> hits_0 = (Map<String, Object>) responseMap.get("hits");
        List<LinkedHashMap> hits_1 = (List<LinkedHashMap>) hits_0.get("hits");
        for (LinkedHashMap hit: hits_1) {
            Map source = (Map) hit.get("_source");
            EntityAuditEventV2 event = new EntityAuditEventV2();
            event.setEntityId((String) source.get(ENTITYID));
            event.setAction(EntityAuditEventV2.EntityAuditActionV2.fromString((String) source.get(ACTION)));
            event.setDetail((Map<String, Object>) source.get(DETAIL));
            event.setUser((String) source.get(USER));
            event.setCreated((long) source.get(CREATED));
            entityAudits.add(event);
        }
        Map<String, Object> aggregationsMap = (Map<String, Object>) responseMap.get("aggregations");
        Map<String, Object> countObject = (Map<String, Object>) hits_0.get("total");
        int totalCount = (int) countObject.get("value");
        searchResult.setEntityAudits(entityAudits);
        searchResult.setAggregations(aggregationsMap);
        searchResult.setTotalCount(totalCount);
        searchResult.setCount(entityAudits.size());
        return searchResult;
    }

    private String performSearchOnIndex(String queryString) throws IOException {
        HttpEntity entity = new NStringEntity(queryString, ContentType.APPLICATION_JSON);
        String endPoint = INDEX_NAME + "/_search";
        Request request = new Request("GET", endPoint);
        request.setEntity(entity);
        Response response = lowLevelClient.performRequest(request);
        return EntityUtils.toString(response.getEntity());
    }

    @Override
    public Set<String> getEntitiesWithTagChanges(long fromTimestamp, long toTimestamp) throws AtlasBaseException {
        throw new NotImplementedException();
    }

    @Override
    public void start() throws AtlasException {
        LOG.info("ESBasedAuditRepo - start!");
        initApplicationProperties();
        startInternal();
    }

    @VisibleForTesting
    void startInternal() throws AtlasException {
        createSession();
    }

    void createSession() throws AtlasException {
        LOG.info("Create ES Session in ES Based Audit Repo");
        setLowLevelClient();
        try {
            boolean indexExists = checkIfIndexExists();
            if (!indexExists) {
                LOG.info("Create ES index for entity audits in ES Based Audit Repo");
                createAuditIndex();
            }
        } catch (IOException e) {
            LOG.error("error", e);
            throw new AtlasException(e);
        }

    }

    private boolean createAuditIndex() throws IOException {
        LOG.info("ESBasedAuditRepo - createAuditIndex!");
        String esMappingsString = getAuditIndexMappings();
        HttpEntity entity = new NStringEntity(esMappingsString, ContentType.APPLICATION_JSON);
        Request request = new Request("PUT", INDEX_NAME);
        request.setEntity(entity);
        Response response = lowLevelClient.performRequest(request);
        int statusCode = response.getStatusLine().getStatusCode();;
        return statusCode == 200 ? true: false;
    }

    private String getAuditIndexMappings() throws IOException {
        String atlasHomeDir  = System.getProperty("atlas.home");
        String elasticsearchSettingsFilePath = (org.apache.commons.lang3.StringUtils.isEmpty(atlasHomeDir) ? "." : atlasHomeDir) + File.separator + "elasticsearch" + File.separator + "es-audit-mappings.json";
        File elasticsearchSettingsFile  = new File(elasticsearchSettingsFilePath);
        String jsonString  = new String(Files.readAllBytes(elasticsearchSettingsFile.toPath()), StandardCharsets.UTF_8);
        return jsonString;
    }

     private boolean checkIfIndexExists() throws IOException {
         Request request = new Request("HEAD", INDEX_NAME);
         Response response = lowLevelClient.performRequest(request);
         int statusCode = response.getStatusLine().getStatusCode();;
         if (statusCode == 200) {
             LOG.info("Entity audits index exists!");
             return true;
         }
         LOG.info("Entity audits index does not exist!");
         return false;
     }

    @Override
    public void stop() throws AtlasException {
        try {
            LOG.info("ESBasedAuditRepo - stop!");
            if (lowLevelClient != null) {
                lowLevelClient.close();
                lowLevelClient = null;
            }
        } catch (IOException e) {
            LOG.error("ESBasedAuditRepo - error while closing es lowlevel client", e);
            throw new AtlasException(e);
        }
    }

    private void setLowLevelClient() throws AtlasException {
        if (lowLevelClient == null) {
            try {
                LOG.info("ESBasedAuditRepo - setLowLevelClient!");
                List<HttpHost> httpHosts = getHttpHosts();

                RestClientBuilder builder = RestClient.builder(httpHosts.get(0));
                builder.setRequestConfigCallback(requestConfigBuilder -> requestConfigBuilder
                        .setConnectTimeout(900000)
                        .setSocketTimeout(900000));

                lowLevelClient = builder.build();
            } catch (AtlasException e) {
                LOG.error("Failed to initialize low level rest client for ES");
                throw new AtlasException(e);
            }
        }
    }

    public static List<HttpHost> getHttpHosts() throws AtlasException {
        List<HttpHost> httpHosts = new ArrayList<>();
        Configuration configuration = ApplicationProperties.get();
        String indexConf = configuration.getString(INDEX_BACKEND_CONF);
        String[] hosts = indexConf.split(",");
        for (String host: hosts) {
            host = host.trim();
            String[] hostAndPort = host.split(":");
            if (hostAndPort.length == 1) {
                httpHosts.add(new HttpHost(hostAndPort[0]));
            } else if (hostAndPort.length == 2) {
                httpHosts.add(new HttpHost(hostAndPort[0], Integer.parseInt(hostAndPort[1])));
            } else {
                throw new AtlasException("Invalid config");
            }
        }
        return httpHosts;
    }

}