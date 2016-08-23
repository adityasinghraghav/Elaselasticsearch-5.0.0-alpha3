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

        import com.google.gson.JsonArray;
        import com.google.gson.JsonObject;
        import com.google.gson.JsonParser;
        import org.elasticsearch.plugin.odoscopesearch.SavedValues;
        import org.elasticsearch.search.internal.InternalSearchHit;
        import org.elasticsearch.search.internal.InternalSearchHits;

        import java.io.BufferedReader;
        import java.io.InputStreamReader;
        import java.io.OutputStream;
        import java.net.HttpURLConnection;
        import java.net.URL;
        import java.nio.charset.Charset;


public class OdoscopeSearchPhaseController {


    public InternalSearchHits Odoscope(InternalSearchHits InHits, long totalHits)
    {

        long size = Math.min(SavedValues.getSize(), InHits.getHits().length);

        String skus[] = new String[(int)size]; //debug case

        String postData = "pids=";



        int counter = -1;
        int count = 0;

        int start = SavedValues.getFrom();
        int stop = SavedValues.getSize()+SavedValues.getFrom();

        if(start>size)
        {
            return new InternalSearchHits(new InternalSearchHit[0], totalHits, InHits.maxScore());
        }


        StringBuilder sss = new StringBuilder();
        InternalSearchHit[] finalhits = InHits.internalHits();

        for (InternalSearchHit hit : finalhits)
        {
            counter++;
            sss = sss.append((hit.getId()).concat(","));
            if (counter < stop && counter >= start)
            {
                skus[count] = hit.getId();
                count++;
            }
        }


        postData = postData.concat(sss.toString());
        if (postData != null && postData.length() > 0 && postData.charAt(postData.length() - 1) == ',')
        {
            postData = postData.substring(0, postData.length() - 1);
        }
        postData = postData.concat(("&limit=".concat(String.valueOf(SavedValues.getSize()))).concat("&offset=".concat(String.valueOf(SavedValues.getFrom()))));


        String pp = "pids=850214,190649,425115&limit=3&offset=0";
        String reponse2 = callOdoscope(SavedValues.getUrl(), pp);

        JsonObject jsonObject = (new JsonParser()).parse(reponse2).getAsJsonObject();
        JsonArray pids = jsonObject.getAsJsonArray("pids");



        int[] pid = new int[pids.size()];

        for(int i = 0; i < pids.size(); i++)
        {
            pid[i]= pids.get(i).getAsInt();
        }




        int[] skore1 = new int[SavedValues.getSize()];
        for(int j = 0; j<SavedValues.getSize(); j++)
        {
            skore1[j] = j + 100;
        }



        InternalSearchHit finalfinalhits[] = new InternalSearchHit[(int)size];


        count = 0;

        for (int itr = finalhits.length - 1; itr > -1; itr--) {
            if (count == (size)) {
                break;
            }

            if ((finalhits[itr].getId()).equals(skus[count])) {

                finalhits[itr].score(skore1[count]);
                finalfinalhits[count] = finalhits[itr];
                count++;
                itr = finalhits.length ;
            }
        }


        return new InternalSearchHits(finalfinalhits, totalHits, InHits.maxScore());

    }

    private String callOdoscope(String requestURL, String postData) {

        StringBuilder responseSB = new StringBuilder();

        try
        {
            URL url = new URL(requestURL);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoOutput(true);

            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setRequestProperty("Content-Length",  String.valueOf(postData.length()));

            // Write data
            OutputStream os = connection.getOutputStream();
            os.write(postData.getBytes(Charset.defaultCharset()));

            // Read response

            BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream(),Charset.defaultCharset()));

            if (br != null) {
                int cp;
                while ((cp = br.read()) != -1) {
                    responseSB.append((char) cp);
                }
                br.close();
            }
            os.close();

        } catch (Exception e) {
            throw new RuntimeException("Exception while calling URL:"+ requestURL, e);
        }

        return responseSB.toString();

    }
}
