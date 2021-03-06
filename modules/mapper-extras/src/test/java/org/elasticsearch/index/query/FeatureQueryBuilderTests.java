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

package org.elasticsearch.index.query;

import org.apache.lucene.document.FeatureField;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingRequest;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.index.mapper.MapperExtrasPlugin;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.query.FeatureQueryBuilder.ScoreFunction;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.test.AbstractQueryTestCase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.Matchers.either;

public class FeatureQueryBuilderTests extends AbstractQueryTestCase<FeatureQueryBuilder> {

    @Override
    protected void initializeAdditionalMappings(MapperService mapperService) throws IOException {
        for (String type : getCurrentTypes()) {
            mapperService.merge(type, new CompressedXContent(Strings.toString(PutMappingRequest.buildFromSimplifiedDef(type,
                    "my_feature_field", "type=feature",
                    "my_negative_feature_field", "type=feature,positive_score_impact=false",
                    "my_feature_vector_field", "type=feature_vector"))), MapperService.MergeReason.MAPPING_UPDATE);
        }
    }

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return Collections.singleton(MapperExtrasPlugin.class);
    }

    @Override
    protected FeatureQueryBuilder doCreateTestQueryBuilder() {
        ScoreFunction function;
        boolean mayUseNegativeField = true;
        switch (random().nextInt(3)) {
        case 0:
            mayUseNegativeField = false;
            function = new ScoreFunction.Log(1 + randomFloat());
            break;
        case 1:
            if (randomBoolean()) {
                function = new ScoreFunction.Saturation();
            } else {
                function = new ScoreFunction.Saturation(randomFloat());
            }
            break;
        case 2:
            function = new ScoreFunction.Sigmoid(randomFloat(), randomFloat());
            break;
        default:
            throw new AssertionError();
        }
        List<String> fields = new ArrayList<>();
        fields.add("my_feature_field");
        fields.add("unmapped_field");
        fields.add("my_feature_vector_field.feature");
        if (mayUseNegativeField) {
            fields.add("my_negative_feature_field");
        }
        
        final String field = randomFrom(fields);
        return new FeatureQueryBuilder(field, function);
    }

    @Override
    protected void doAssertLuceneQuery(FeatureQueryBuilder queryBuilder, Query query, SearchContext context) throws IOException {
        Class<?> expectedClass = FeatureField.newSaturationQuery("", "", 1, 1).getClass();
        assertThat(query, either(instanceOf(MatchNoDocsQuery.class)).or(instanceOf(expectedClass)));
    }

    public void testDefaultScoreFunction() throws IOException {
        assumeTrue("test runs only when at least a type is registered", getCurrentTypes().length > 0);
        String query = "{\n" +
                "    \"feature\" : {\n" +
                "        \"field\": \"my_feature_field\"\n" +
                "    }\n" +
                "}";
        Query parsedQuery = parseQuery(query).toQuery(createShardContext());
        assertEquals(FeatureField.newSaturationQuery("_feature", "my_feature_field"), parsedQuery);
    }

    public void testIllegalField() throws IOException {
        assumeTrue("test runs only when at least a type is registered", getCurrentTypes().length > 0);
        String query = "{\n" +
                "    \"feature\" : {\n" +
                "        \"field\": \"" + STRING_FIELD_NAME + "\"\n" +
                "    }\n" +
                "}";
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> parseQuery(query).toQuery(createShardContext()));
        assertEquals("[feature] query only works on [feature] fields and features of [feature_vector] fields, not [text]", e.getMessage());
    }

    public void testIllegalCombination() throws IOException {
        assumeTrue("test runs only when at least a type is registered", getCurrentTypes().length > 0);
        String query = "{\n" +
                "    \"feature\" : {\n" +
                "        \"field\": \"my_negative_feature_field\",\n" +
                "        \"log\" : {\n" +
                "            \"scaling_factor\": 4.5\n" +
                "        }\n" +
                "    }\n" +
                "}";
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> parseQuery(query).toQuery(createShardContext()));
        assertEquals(
                "Cannot use the [log] function with a field that has a negative score impact as it would trigger negative scores",
                e.getMessage());
    }
}
