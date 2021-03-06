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
package org.elasticsearch.hadoop.cascading;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.logging.LogFactory;
import org.elasticsearch.hadoop.cfg.Settings;
import org.elasticsearch.hadoop.cfg.SettingsManager;
import org.elasticsearch.hadoop.rest.InitializationUtils;
import org.elasticsearch.hadoop.rest.RestRepository;
import org.elasticsearch.hadoop.rest.ScrollQuery;
import org.elasticsearch.hadoop.serialization.JdkValueReader;
import org.elasticsearch.hadoop.serialization.SerializationUtils;

import cascading.flow.FlowProcess;
import cascading.scheme.Scheme;
import cascading.scheme.SinkCall;
import cascading.scheme.SourceCall;
import cascading.tap.Tap;
import cascading.tuple.Fields;
import cascading.tuple.TupleEntry;
import cascading.tuple.Tuples;

/**
 * Cascading Scheme handling
 */
@SuppressWarnings("serial")
class ESLocalScheme extends Scheme<Properties, ScrollQuery, Object, Object[], Object[]> {

    private final String resource;
    private final String host;
    private final int port;
    private transient RestRepository client;

    ESLocalScheme(String host, int port, String index, Fields fields) {
        this.resource = index;
        this.host = host;
        this.port = port;
        if (fields != null) {
            setSinkFields(fields);
            setSourceFields(fields);
        }
    }

    @Override
    public void sourcePrepare(FlowProcess<Properties> flowProcess, SourceCall<Object[], ScrollQuery> sourceCall) throws IOException {
        super.sourcePrepare(flowProcess, sourceCall);

        Fields sourceCallFields = sourceCall.getIncomingEntry().getFields();
        Fields sourceFields = (sourceCallFields.isDefined() ? sourceCallFields : getSourceFields());
        List<String> tupleNames = resolveNames(sourceFields);

        Object[] context = new Object[1];
        context[0] = tupleNames;
        sourceCall.setContext(context);
    }

    @Override
    public void sourceCleanup(FlowProcess<Properties> flowProcess, SourceCall<Object[], ScrollQuery> sourceCall) throws IOException {
        sourceCall.getInput().close();
        cleanupClient();
    }

    @Override
    public void sinkCleanup(FlowProcess<Properties> flowProcess, SinkCall<Object[], Object> sinkCall) throws IOException {
        cleanupClient();
    }

    private void cleanupClient() throws IOException {
        if (client != null) {
            client.close();
            client = null;
        }
    }

    @Override
    public void sinkPrepare(FlowProcess<Properties> flowProcess, SinkCall<Object[], Object> sinkCall) throws IOException {
        super.sinkPrepare(flowProcess, sinkCall);

        Fields sinkCallFields = sinkCall.getOutgoingEntry().getFields();
        Fields sinkFields = (sinkCallFields.isDefined() ? sinkCallFields : getSinkFields());
        List<String> tupleNames = resolveNames(sinkFields);

        Object[] context = new Object[1];
        context[0] = tupleNames;
        sinkCall.setContext(context);
    }

    private List<String> resolveNames(Fields fields) {

        //TODO: add handling of undefined types (Fields.UNKNOWN/ALL/RESULTS...)
        if (fields == null || !fields.isDefined()) {
            // use auto-generated name
            return Collections.emptyList();
        }

        int size = fields.size();
        List<String> names = new ArrayList<String>(size);
        for (int fieldIndex = 0; fieldIndex < size; fieldIndex++) {
            names.add(fields.get(fieldIndex).toString());
        }

        return names;
    }

    @Override
    public void sourceConfInit(FlowProcess<Properties> flowProcess, Tap<Properties, ScrollQuery, Object> tap, Properties conf) {
        initClient(conf);
    }

    @Override
    public void sinkConfInit(FlowProcess<Properties> flowProcess, Tap<Properties, ScrollQuery, Object> tap, Properties conf) {
        initClient(conf);

        InitializationUtils.checkIndexExistence(SettingsManager.loadFrom(conf), client);
    }

    private void initClient(Properties props) {
        if (client == null) {
            Settings settings = SettingsManager.loadFrom(props).setHosts(host).setPort(port).setResource(resource);

            SerializationUtils.setValueWriterIfNotSet(settings, CascadingValueWriter.class, LogFactory.getLog(ESTap.class));
            SerializationUtils.setValueReaderIfNotSet(settings, JdkValueReader.class, LogFactory.getLog(ESTap.class));
            settings.save();
            client = new RestRepository(settings);
        }
    }

    @Override
    public boolean source(FlowProcess<Properties> flowProcess, SourceCall<Object[], ScrollQuery> sourceCall) throws IOException {
        ScrollQuery query = sourceCall.getInput();
        if (query.hasNext()) {
            @SuppressWarnings("unchecked")
            Map<String, ?> map = (Map<String, ?>) query.next()[1];
            TupleEntry tuples = sourceCall.getIncomingEntry();

            // TODO: verify ordering guarantees
            //Set<String> keys = map.keySet();
            //tuples.set(new TupleEntry(new Fields(keys.toArray(new String[keys.size()])),

            tuples.setTuple(Tuples.create(new ArrayList<Object>(map.values())));
            return true;
        }
        return false;
    }

    @Override
    public void sink(FlowProcess<Properties> flowProcess, SinkCall<Object[], Object> sinkCall) throws IOException {
        client.writeToIndex(sinkCall);
    }
}