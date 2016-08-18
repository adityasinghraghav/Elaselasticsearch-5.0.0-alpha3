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

package org.elasticsearch.plugin.odoscopesearch;

import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentLocation;
import org.elasticsearch.common.xcontent.XContentParser;

public class SearchRequestParser {

    public int from;
    public int size;
    public boolean sort = false;


    @Inject
    public SearchRequestParser()
    {
        from = 0;
        size = 10;
    }

    public void parseSource(BytesReference source) throws OdoscopeSearchParseException {
        // nothing to parse...
        String fieldName = null;

        int StartObjects = 0;
        boolean flag =  true;

        if (source == null || source.length() == 0) {
            return;
        }
        XContentParser parser = null;
        try {
            String currentFieldName = null;
            parser = XContentFactory.xContent(source).createParser(source);
            XContentParser.Token token;


            while (flag) {

                token = parser.nextToken();

                if(token == XContentParser.Token.START_OBJECT)
                {
                    StartObjects++;
                }
                else if(token == XContentParser.Token.END_OBJECT)
                {
                    StartObjects--;
                }

                else if (token == XContentParser.Token.FIELD_NAME)
                {
                    currentFieldName = parser.currentName();
                    if (currentFieldName.equals("sort"))
                    {
                        sort = true;
                    }
                }

                else if (token.isValue() && StartObjects == 1)
                {
                    if (currentFieldName != null) {
                        switch (currentFieldName) {

                            case "from":
                                from = parser.intValue();
                                break;
                            case "size":
                                size = parser.intValue();
                                break;
                        }
                    }
                }

                if(StartObjects == 0)
                {
                    flag = false;
                }

            }
        } catch (Throwable e) {
            String sSource = "_na_";
            try {
                sSource = XContentHelper.convertToJson(source, false);
            } catch (Throwable e1) {
                // ignore
            }
            XContentLocation location = parser != null ? parser.getTokenLocation() : null;

            throw new OdoscopeSearchParseException("failed to parse search source [" + sSource + "] at ["+ location.toString()+"]", e);
        } finally {
            if (parser != null) {
                parser.close();
            }
        }
    }

    public int getSize() {
        return size;
    }
    public int getFrom() {
        return from;
    }
    public boolean getsort(){return sort;}
}
