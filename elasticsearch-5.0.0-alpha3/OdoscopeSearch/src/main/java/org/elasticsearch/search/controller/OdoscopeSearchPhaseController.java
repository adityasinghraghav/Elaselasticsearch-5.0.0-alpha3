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

package org.elasticsearch.search.controller;

import org.elasticsearch.plugin.odoscopesearch.SavedValues;
import org.elasticsearch.search.internal.InternalSearchHit;
import org.elasticsearch.search.internal.InternalSearchHits;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;

public class OdoscopeSearchPhaseController {


    public InternalSearchHits Odoscope(InternalSearchHits InHits, long totalHits)
    {

        int size = Math.min(SavedValues.getS(), InHits.getHits().length);
        String skus[] = new String[size];
        String query = ("{\"from\":").concat((String.valueOf(SavedValues.getF()).concat(((",\"size\":").concat((String.valueOf(SavedValues.getS()).concat(",\"results\":[")))))));

        int counter = -1;
        int count = 0;

        int start = SavedValues.getF();
        int stop = SavedValues.getS()+SavedValues.getF();

        if(start>size)
        {
            return new InternalSearchHits(new InternalSearchHit[0], totalHits, InHits.maxScore());
        }

        StringBuilder ss = new StringBuilder();
        InternalSearchHit[] finalhits = InHits.internalHits();

        for (InternalSearchHit hit : finalhits)
        {
            counter++;
            ss = ss.append(("{\"SKU\":").concat((hit.getId()).concat((",\"score\":").concat((String.valueOf(hit.getScore()).concat("},"))))));

            if (counter < stop && counter >= start)
            {
                skus[count] = hit.getId();
                count++;
            }
        }

        query = query.concat(ss.toString());
        if (query != null && query.length() > 0 && query.charAt(query.length() - 1) == ',')
        {
            query = query.substring(0, query.length() - 1);
        }
        query = query.concat("]}");


        String response1= callURL("http://odoscope.cloud/praxisdienst.de/decisions?maxItems=20&token=XfRqxAgR7XwGW3qzmj2Mxw&categoryID=2dd41e75a881635aa52be72e7f6d2eeb&prodId=421109&hostname=praxisdienst.de");
        String response = "{\n" +
            "  \"variants\": [\n" +
            "   {\n" +
            "    \"variantID\": \"311201\",\n" +
            "    \"score\": 18879\n" +
            "   },\n" +
            "   {\n" +
            "    \"variantID\": \"311101\",\n" +
            "    \"score\": 5275\n" +
            "   },\n" +
            "   {\n" +
            "    \"variantID\": \"421100\",\n" +
            "    \"score\": 5059\n" +
            "   },\n" +
            "   {\n" +
            "    \"variantID\": \"131185\",\n" +
            "    \"score\": 4344\n" +
            "   },\n" +
            "   {\n" +
            "    \"variantID\": \"602535\",\n" +
            "    \"score\": 4187\n" +
            "   },\n" +
            "   {\n" +
            "    \"variantID\": \"425115\",\n" +
            "    \"score\": 3992\n" +
            "   },\n" +
            "   {\n" +
            "    \"variantID\": \"602272\",\n" +
            "    \"score\": 3969\n" +
            "   },\n" +
            "   {\n" +
            "    \"variantID\": \"602221\",\n" +
            "    \"score\": 3840\n" +
            "   },\n" +
            "   {\n" +
            "    \"variantID\": \"602540\",\n" +
            "    \"score\": 3823\n" +
            "   },\n" +
            "   {\n" +
            "    \"variantID\": \"350000\",\n" +
            "    \"score\": 3771\n" +
            "   },\n" +
            "   {\n" +
            "    \"variantID\": \"360250\",\n" +
            "    \"score\": 3439\n" +
            "   },\n" +
            "   {\n" +
            "    \"variantID\": \"312101\",\n" +
            "    \"score\": 3359\n" +
            "   },\n" +
            "   {\n" +
            "    \"variantID\": \"130400\",\n" +
            "    \"score\": 3332\n" +
            "   },\n" +
            "   {\n" +
            "    \"variantID\": \"603034\",\n" +
            "    \"score\": 3294\n" +
            "   },\n" +
            "   {\n" +
            "    \"variantID\": \"430105blau\",\n" +
            "    \"score\": 3286\n" +
            "   },\n" +
            "   {\n" +
            "    \"variantID\": \"425100\",\n" +
            "    \"score\": 3092\n" +
            "   },\n" +
            "   {\n" +
            "    \"variantID\": \"126022\",\n" +
            "    \"score\": 3006\n" +
            "   },\n" +
            "   {\n" +
            "    \"variantID\": \"318401\",\n" +
            "    \"score\": 2782\n" +
            "   },\n" +
            "   {\n" +
            "    \"variantID\": \"314101\",\n" +
            "    \"score\": 2570\n" +
            "   },\n" +
            "   {\n" +
            "    \"variantID\": \"350600\",\n" +
            "    \"score\": 2483\n" +
            "   }\n" +
            "  ],\n" +
            "  \"responseStatus\": {\n" +
            "   \"responseCode\": 200,\n" +
            "   \"responseMessage\": \"\"\n" +
            "  }\n" +
            " }";


        String delims = "[,.?/|\n}{\" /:\" ]+";
        String[] tokens = response.split(delims);

        String skids1[] = new String[20] ; //Saved.value.getS();
        String skore1[] = new String[20];

        int k;
        for (k = 0; k < tokens.length - 1; k++) {
            if (tokens[k].equals("variantID"))
                break;
        }

        int ct = 0;

        while (!(tokens[k].equals("]"))) {
            skids1[ct] = tokens[k + 1];
            skore1[ct] = tokens[k + 3];
            ct++;
            k = k + 4;
        }



        InternalSearchHit finalfinalhits[] = new InternalSearchHit[size];


        count = 0;

        for (int itr = finalhits.length - 1; itr > -1; itr--) {
            if (count == (size)) {
                break;
            }

            if ((finalhits[itr].getId()).equals(skus[count])) {

                finalhits[itr].score(Float.valueOf(skore1[count]));
                finalfinalhits[count] = finalhits[itr];
                count++;
                itr = finalhits.length ;
            }
        }

        InternalSearchHits searchHits = new InternalSearchHits(finalfinalhits, totalHits, InHits.maxScore());

        return searchHits;

    }

    private static String callURL(String myURL) {

        StringBuilder sb = new StringBuilder();
        URLConnection urlConn = null;
        InputStreamReader in = null;

        try {

            URL url = new URL(myURL);

            urlConn = url.openConnection();
            //urlConn.addRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64; rv:40.0) Gecko/20100101 Firefox/40.1");
//            System.out.println(urlConn.getHeaderFields());


            if (urlConn != null)
                urlConn.setReadTimeout(60 * 1000);

            if (urlConn != null && urlConn.getInputStream() != null)
            {

                in = new InputStreamReader(urlConn.getInputStream(),
                    Charset.defaultCharset());

                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(urlConn.getInputStream(),
                    Charset.defaultCharset()));

                if (bufferedReader != null) {
                    int cp;
                    while ((cp = bufferedReader.read()) != -1) {
                        sb.append((char) cp);
                    }
                    bufferedReader.close();
                }
            }
            in.close();
        } catch (Exception e) {
            throw new RuntimeException("Exception while calling URL:"+ myURL, e);
        }
        return sb.toString();
    }


}
