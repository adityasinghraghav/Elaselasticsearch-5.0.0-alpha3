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

package org.elasticsearch.search.aggregations.bucket;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.automaton.RegExp;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.script.Script;
import org.elasticsearch.search.aggregations.BaseAggregationTestCase;
import org.elasticsearch.search.aggregations.bucket.significant.SignificantTermsAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.significant.heuristics.ChiSquare;
import org.elasticsearch.search.aggregations.bucket.significant.heuristics.GND;
import org.elasticsearch.search.aggregations.bucket.significant.heuristics.JLHScore;
import org.elasticsearch.search.aggregations.bucket.significant.heuristics.MutualInformation;
import org.elasticsearch.search.aggregations.bucket.significant.heuristics.PercentageScore;
import org.elasticsearch.search.aggregations.bucket.significant.heuristics.ScriptHeuristic;
import org.elasticsearch.search.aggregations.bucket.significant.heuristics.SignificanceHeuristic;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregatorFactory.ExecutionMode;
import org.elasticsearch.search.aggregations.bucket.terms.support.IncludeExclude;
import java.util.SortedSet;
import java.util.TreeSet;

public class SignificantTermsTests extends BaseAggregationTestCase<SignificantTermsAggregationBuilder> {

    private static final String[] executionHints;

    static {
        ExecutionMode[] executionModes = ExecutionMode.values();
        executionHints = new String[executionModes.length];
        for (int i = 0; i < executionModes.length; i++) {
            executionHints[i] = executionModes[i].toString();
        }
    }

    @Override
    protected SignificantTermsAggregationBuilder createTestAggregatorBuilder() {
        String name = randomAsciiOfLengthBetween(3, 20);
        SignificantTermsAggregationBuilder factory = new SignificantTermsAggregationBuilder(name, null);
        String field = randomAsciiOfLengthBetween(3, 20);
        int randomFieldBranch = randomInt(2);
        switch (randomFieldBranch) {
        case 0:
            factory.field(field);
            break;
        case 1:
            factory.field(field);
            factory.script(new Script("_value + 1"));
            break;
        case 2:
            factory.script(new Script("doc[" + field + "] + 1"));
            break;
        default:
            fail();
        }
        if (randomBoolean()) {
            factory.missing("MISSING");
        }
        if (randomBoolean()) {
            int size = randomInt(4);
            switch (size) {
            case 0:
                break;
            case 1:
            case 2:
            case 3:
            case 4:
                size = randomInt();
                break;
            default:
                fail();
            }
            factory.bucketCountThresholds().setRequiredSize(size);

        }
        if (randomBoolean()) {
            int shardSize = randomInt(4);
            switch (shardSize) {
            case 0:
                break;
            case 1:
            case 2:
            case 3:
            case 4:
                shardSize = randomInt();
                break;
            default:
                fail();
            }
            factory.bucketCountThresholds().setShardSize(shardSize);
        }
        if (randomBoolean()) {
            int minDocCount = randomInt(4);
            switch (minDocCount) {
            case 0:
                break;
            case 1:
            case 2:
            case 3:
            case 4:
                minDocCount = randomInt();
                break;
            }
            factory.bucketCountThresholds().setMinDocCount(minDocCount);
        }
        if (randomBoolean()) {
            int shardMinDocCount = randomInt(4);
            switch (shardMinDocCount) {
            case 0:
                break;
            case 1:
            case 2:
            case 3:
            case 4:
                shardMinDocCount = randomInt();
                break;
            default:
                fail();
            }
            factory.bucketCountThresholds().setShardMinDocCount(shardMinDocCount);
        }
        if (randomBoolean()) {
            factory.executionHint(randomFrom(executionHints));
        }
        if (randomBoolean()) {
            factory.format("###.##");
        }
        if (randomBoolean()) {
            IncludeExclude incExc = null;
            switch (randomInt(5)) {
            case 0:
                incExc = new IncludeExclude(new RegExp("foobar"), null);
                break;
            case 1:
                incExc = new IncludeExclude(null, new RegExp("foobaz"));
                break;
            case 2:
                incExc = new IncludeExclude(new RegExp("foobar"), new RegExp("foobaz"));
                break;
            case 3:
                SortedSet<BytesRef> includeValues = new TreeSet<>();
                int numIncs = randomIntBetween(1, 20);
                for (int i = 0; i < numIncs; i++) {
                    includeValues.add(new BytesRef(randomAsciiOfLengthBetween(1, 30)));
                }
                SortedSet<BytesRef> excludeValues = null;
                incExc = new IncludeExclude(includeValues, excludeValues);
                break;
            case 4:
                SortedSet<BytesRef> includeValues2 = null;
                SortedSet<BytesRef> excludeValues2 = new TreeSet<>();
                int numExcs2 = randomIntBetween(1, 20);
                for (int i = 0; i < numExcs2; i++) {
                    excludeValues2.add(new BytesRef(randomAsciiOfLengthBetween(1, 30)));
                }
                incExc = new IncludeExclude(includeValues2, excludeValues2);
                break;
            case 5:
                SortedSet<BytesRef> includeValues3 = new TreeSet<>();
                int numIncs3 = randomIntBetween(1, 20);
                for (int i = 0; i < numIncs3; i++) {
                    includeValues3.add(new BytesRef(randomAsciiOfLengthBetween(1, 30)));
                }
                SortedSet<BytesRef> excludeValues3 = new TreeSet<>();
                int numExcs3 = randomIntBetween(1, 20);
                for (int i = 0; i < numExcs3; i++) {
                    excludeValues3.add(new BytesRef(randomAsciiOfLengthBetween(1, 30)));
                }
                incExc = new IncludeExclude(includeValues3, excludeValues3);
                break;
            default:
                fail();
            }
            factory.includeExclude(incExc);
        }
        if (randomBoolean()) {
            SignificanceHeuristic significanceHeuristic = null;
            switch (randomInt(5)) {
            case 0:
                significanceHeuristic = new PercentageScore();
                break;
            case 1:
                significanceHeuristic = new ChiSquare(randomBoolean(), randomBoolean());
                break;
            case 2:
                significanceHeuristic = new GND(randomBoolean());
                break;
            case 3:
                significanceHeuristic = new MutualInformation(randomBoolean(), randomBoolean());
                break;
            case 4:
                significanceHeuristic = new ScriptHeuristic(new Script("foo"));
                break;
            case 5:
                significanceHeuristic = new JLHScore();
                break;
            default:
                fail();
            }
            factory.significanceHeuristic(significanceHeuristic);
        }
        if (randomBoolean()) {
            factory.backgroundFilter(QueryBuilders.termsQuery("foo", "bar"));
        }
        return factory;
    }

}
