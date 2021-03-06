/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.elasticsearch.hadoop.rest;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.map.ObjectMapper;
import org.elasticsearch.hadoop.cfg.Settings;
import org.elasticsearch.hadoop.rest.Request.Method;
import org.elasticsearch.hadoop.rest.dto.Node;
import org.elasticsearch.hadoop.util.BytesArray;
import org.elasticsearch.hadoop.util.NodeUtils;
import org.elasticsearch.hadoop.util.StringUtils;
import org.elasticsearch.hadoop.util.unit.TimeValue;

import static org.elasticsearch.hadoop.rest.Request.Method.*;

public class RestClient implements Closeable {

    private static final Log log = LogFactory.getLog(RestClient.class);

    private NetworkClient network;
    private ObjectMapper mapper = new ObjectMapper();
    private TimeValue scrollKeepAlive;
    private boolean indexReadMissingAsEmpty;

    public enum HEALTH {
        RED, YELLOW, GREEN
    }

    public RestClient(Settings settings) {
        network = new NetworkClient(settings, NodeUtils.nodes(settings));

        scrollKeepAlive = TimeValue.timeValueMillis(settings.getScrollKeepAlive());
        indexReadMissingAsEmpty = settings.getIndexReadMissingAsEmpty();
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public List<String> discoverNodes() throws IOException {
        Map<String, Map> nodes = (Map<String, Map>) get("_cluster/nodes", "nodes");

        List<String> hosts = new ArrayList<String>(nodes.size());

        for (Map value : nodes.values()) {
            String inet = (String) value.get("http_address");
            if (StringUtils.hasText(inet)) {
                int startIp = inet.indexOf("/") + 1;
                int endIp = inet.indexOf("]");
                inet = inet.substring(startIp, endIp);
                hosts.add(inet);
            }
        }

        return hosts;
    }

    private <T> T get(String q, String string) throws IOException {
        return parseContent(execute(GET, q), string);
    }

    @SuppressWarnings("unchecked")
    private <T> T parseContent(byte[] content, String string) throws IOException {
        // create parser manually to lower Jackson requirements
        JsonParser jsonParser = mapper.getJsonFactory().createJsonParser(content);
        Map<String, Object> map = mapper.readValue(jsonParser, Map.class);
        return (T) (string != null ? map.get(string) : map);
    }

    @SuppressWarnings("unchecked")
    public void bulk(Resource resource, BytesArray buffer) throws IOException {
        //empty buffer, ignore
        if (buffer.size() == 0) {
            return;
        }

        if (log.isTraceEnabled()) {
            log.trace("Sending bulk request " + buffer.toString());
        }

        byte[] content = execute(PUT, resource.bulk(), buffer);

        // create parser manually to lower Jackson requirements
        JsonParser jsonParser = mapper.getJsonFactory().createJsonParser(content);
        Map<String, Object> map = mapper.readValue(jsonParser, Map.class);
        List<Object> items = (List<Object>) map.get("items");

        for (Object item : items) {
            Map<String, String> messages = (Map<String, String>) ((Map) item).values().iterator().next();
            String message = messages.get("error");
            if (StringUtils.hasText(message)) {
                throw new IllegalStateException(String.format(
                        "Bulk request on index [%s] failed; at least one error reported [%s]", resource.indexAndType(), message));
            }
        }

        if (log.isTraceEnabled()) {
            log.trace("Received bulk response " + StringUtils.asUTFString(content));
        }
    }

    public void refresh(Resource resource) throws IOException {
        execute(POST, resource.refresh());
    }

    public void deleteIndex(String index) throws IOException {
        execute(DELETE, index);
    }

    public List<List<Map<String, Object>>> targetShards(Resource resource) throws IOException {
        List<List<Map<String, Object>>> shardsJson = null;

        if (indexReadMissingAsEmpty) {
            Response res = execute(GET, resource.targetShards(), false);
            if (res.status() == HttpStatus.NOT_FOUND) {
                shardsJson = Collections.emptyList();
            }
            else {
                shardsJson = parseContent(res.body(), "shards");
            }
        }
        else {
            shardsJson = get(resource.targetShards(), "shards");
        }

        return shardsJson;
    }

    public Map<String, Node> getNodes() throws IOException {
        Map<String, Map<String, Object>> nodesData = get("_nodes", "nodes");
        Map<String, Node> nodes = new LinkedHashMap<String, Node>();

        for (Entry<String, Map<String, Object>> entry : nodesData.entrySet()) {
            Node node = new Node(entry.getKey(), entry.getValue());
            nodes.put(entry.getKey(), node);
        }
        return nodes;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMapping(String query) throws IOException {
        return (Map<String, Object>) get(query, null);
    }

    @Override
    public void close() {
        network.close();
    }

    byte[] execute(Request request) throws IOException {
        return execute(request, true).body();
    }

    byte[] execute(Method method, String path) throws IOException {
        return execute(new SimpleRequest(method, null, path));
    }

    Response execute(Method method, String path, boolean checkStatus) throws IOException {
        return execute(new SimpleRequest(method, null, path), false);
    }

    byte[] execute(Method method, String path, BytesArray buffer) throws IOException {
        return execute(new SimpleRequest(method, null, path, null, buffer));
    }

    Response execute(Request request, boolean checkStatus) throws IOException {
        Response response = network.execute(request);

        if (checkStatus && response.hasFailed()) {
            String bodyAsString = StringUtils.asUTFString(response.body());
            throw new IllegalStateException(String.format("[%s] on [%s] failed; server[%s] returned [%s]",
                    request.method().name(), request.path(), response.uri(), bodyAsString));
        }

        return response;
    }

    public String[] scan(String query, BytesArray body) throws IOException {
        Map<String, Object> scan = parseContent(execute(POST, query, body), null);

        String[] data = new String[2];
        data[0] = scan.get("_scroll_id").toString();
        data[1] = ((Map<?, ?>) scan.get("hits")).get("total").toString();
        return data;
    }

    public byte[] scroll(String scrollId) throws IOException {
        // use post instead of get to avoid some weird encoding issues (caused by the long URL)
        return execute(POST, "_search/scroll?scroll=" + scrollKeepAlive.toString(), new BytesArray(scrollId.getBytes(StringUtils.UTF_8)));
    }

    public boolean exists(String indexOrType) throws IOException {
        return (execute(HEAD, indexOrType, false).hasSucceeded());
    }

    public boolean touch(String indexOrType) throws IOException {
        return (execute(PUT, indexOrType, false).hasSucceeded());
    }

    public void putMapping(String index, String mapping, byte[] bytes) throws IOException {
        // create index first (if needed) - it might return 403
        touch(index);

        execute(PUT, mapping, new BytesArray(bytes));
    }

    public boolean health(String index, HEALTH health, TimeValue timeout) throws IOException {
        StringBuilder sb = new StringBuilder("/_cluster/health/");
        sb.append(index);
        sb.append("?wait_for_status=");
        sb.append(health.name().toLowerCase(Locale.ENGLISH));
        sb.append("&timeout=");
        sb.append(timeout.toString());

        return (Boolean.TRUE.equals(get(sb.toString(), "timed_out")));
    }
}