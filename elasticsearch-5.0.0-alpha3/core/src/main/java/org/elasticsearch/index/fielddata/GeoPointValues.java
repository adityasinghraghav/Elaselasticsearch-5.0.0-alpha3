/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.fielddata;

import org.elasticsearch.common.geo.GeoPoint;

/**
 * Per-document geo-point values.
 */
public abstract class GeoPointValues {

    /**
     * Get the {@link GeoPoint} associated with <code>docID</code>.
     * The returned {@link GeoPoint} might be reused across calls.
     * If the given <code>docID</code> does not have a value then the returned
     * geo point mught have both latitude and longitude set to 0.
     */
    public abstract GeoPoint get(int docID);

}
