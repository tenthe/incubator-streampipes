/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.streampipes.sinks.internal.jvm.datalake;

import org.apache.streampipes.commons.exceptions.SpRuntimeException;
import org.apache.streampipes.model.runtime.Event;
import org.apache.streampipes.model.runtime.field.PrimitiveField;
import org.apache.streampipes.model.schema.EventProperty;
import org.apache.streampipes.model.schema.EventPropertyPrimitive;
import org.apache.streampipes.model.schema.EventSchema;
import org.apache.streampipes.vocabulary.XSD;
import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.Point;
import org.influxdb.dto.Pong;
import org.influxdb.dto.Query;
import org.influxdb.dto.QueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Code is the same as InfluxDB (org.apache.streampipes.sinks.databases.jvm.influxdb) sink. Changes applied here should also be applied in the InfluxDB sink
 */
public class DataLakeInfluxDbClient {

    private static final Logger LOG = LoggerFactory.getLogger(DataLakeInfluxDbClient.class);

    private final String measureName;
    private final String timestampField;
    private final Integer batchSize;
    private final Integer flushDuration;

    private InfluxDB influxDb = null;
    private final InfluxDbConnectionSettings settings;
    private final EventSchema originalEventSchema;

    Map<String, String> targetRuntimeNames = new HashMap<>();

    DataLakeInfluxDbClient(InfluxDbConnectionSettings settings,
                           String timestampField,
                           Integer batchSize,
                           Integer flushDuration,
                           EventSchema originalEventSchema) throws SpRuntimeException {
        this.settings = settings;
        this.originalEventSchema = originalEventSchema;
        this.timestampField = timestampField;
        this.batchSize = batchSize;
        this.flushDuration = flushDuration;
        this.measureName = settings.getMeasureName();

        prepareSchema();
        connect();
    }

    private void prepareSchema() {
        originalEventSchema
                .getEventProperties()
                .forEach(ep -> targetRuntimeNames.put(ep.getRuntimeName(), DataLakeUtils.sanitizePropertyRuntimeName(ep.getRuntimeName())));
    }

    /**
     * Connects to the InfluxDB Server, sets the database and initializes the batch-behaviour
     *
     * @throws SpRuntimeException If not connection can be established or if the database could not
     * be found
     */
    private void connect() throws SpRuntimeException {
        // Connecting to the server
        // "http://" must be in front
        String urlAndPort = settings.getInfluxDbHost() + ":" + settings.getInfluxDbPort();
        influxDb = InfluxDBFactory.connect(urlAndPort, settings.getUser(), settings.getPassword());

        // Checking, if server is available
        Pong response = influxDb.ping();
        if (response.getVersion().equalsIgnoreCase("unknown")) {
            throw new SpRuntimeException("Could not connect to InfluxDb Server: " + urlAndPort);
        }

        String databaseName = settings.getDatabaseName();
        // Checking whether the database exists
        if(!databaseExists(databaseName)) {
            LOG.info("Database '" + databaseName + "' not found. Gets created ...");
            createDatabase(databaseName);
        }

        // setting up the database
        influxDb.setDatabase(databaseName);
        influxDb.enableBatch(batchSize, flushDuration, TimeUnit.MILLISECONDS);
    }

    /**
     * Checks whether the given database exists. Needs a working connection to an InfluxDB Server
     * ({@link DataLakeInfluxDbClient#influxDb} needs to be initialized)
     *
     * @param dbName The name of the database, the method should look for
     * @return True if the database exists, false otherwise
     */
    private boolean databaseExists(String dbName) {
        QueryResult queryResult = influxDb.query(new Query("SHOW DATABASES", ""));
        for(List<Object> a : queryResult.getResults().get(0).getSeries().get(0).getValues()) {
            if(a.get(0).equals(dbName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates a new database with the given name
     *
     * @param dbName The name of the database which should be created
     */
    private void createDatabase(String dbName) throws SpRuntimeException {
        if(!dbName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new SpRuntimeException("Databasename '" + dbName + "' not allowed. Allowed names: ^[a-zA-Z_][a-zA-Z0-9_]*$");
        }
        influxDb.query(new Query("CREATE DATABASE \"" + dbName + "\"", ""));
    }

    /**
     * Saves an event to the connnected InfluxDB database
     *
     * @param event The event which should be saved
     * @throws SpRuntimeException If the column name (key-value of the event map) is not allowed
     */
    void save(Event event, EventSchema schema) throws SpRuntimeException {
        if (event == null) {
            throw new SpRuntimeException("event is null");
        }

        Long timestampValue = event.getFieldBySelector(timestampField).getAsPrimitive().getAsLong();
        Point.Builder p = Point.measurement(measureName).time(timestampValue, TimeUnit.MILLISECONDS);

        for (EventProperty ep : schema.getEventProperties()) {
            if (ep instanceof EventPropertyPrimitive) {
                String runtimeName = ep.getRuntimeName();

                if (!timestampField.endsWith(runtimeName)) {
                    String preparedRuntimeName = targetRuntimeNames.get(runtimeName);
                    PrimitiveField eventPropertyPrimitiveField = event.getFieldByRuntimeName(runtimeName).getAsPrimitive();

                    // store property as tag when the field is a dimension property
                    if ("DIMENSION_PROPERTY".equals(ep.getPropertyScope())) {
                        p.tag(preparedRuntimeName, eventPropertyPrimitiveField.getAsString());
                    } else {
                        try {
                            // Store property according to property type
                            String runtimeType = ((EventPropertyPrimitive) ep).getRuntimeType();
                            if (XSD._integer.toString().equals(runtimeType)) {
                                p.addField(preparedRuntimeName, eventPropertyPrimitiveField.getAsInt());
                            } else if (XSD._float.toString().equals(runtimeType)) {
                                p.addField(preparedRuntimeName, eventPropertyPrimitiveField.getAsFloat());
                            } else if (XSD._double.toString().equals(runtimeType)) {
                                p.addField(preparedRuntimeName, eventPropertyPrimitiveField.getAsDouble());
                            } else if (XSD._boolean.toString().equals(runtimeType)) {
                                p.addField(preparedRuntimeName, eventPropertyPrimitiveField.getAsBoolean());
                            } else if (XSD._long.toString().equals(runtimeType)) {
                                p.addField(preparedRuntimeName, eventPropertyPrimitiveField.getAsLong());
                            } else {
                                p.addField(preparedRuntimeName, eventPropertyPrimitiveField.getAsString());
                            }
                        } catch (NumberFormatException e) {
                            LOG.warn("Wrong number format for field {}, ignoring.", preparedRuntimeName);
                        }
                    }
                }
            }
        }

        influxDb.write(p.build());
    }

    /**
     * Shuts down the connection to the InfluxDB server
     */
    void stop() {
        influxDb.flush();
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        influxDb.close();
    }

}
