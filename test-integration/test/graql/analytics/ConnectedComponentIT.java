/*
 * GRAKN.AI - THE KNOWLEDGE GRAPH
 * Copyright (C) 2018 Grakn Labs Ltd
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ai.grakn.test.graql.analytics;

import ai.grakn.GraknSession;
import ai.grakn.GraknTx;
import ai.grakn.GraknTxType;
import ai.grakn.concept.Attribute;
import ai.grakn.concept.AttributeType;
import ai.grakn.concept.ConceptId;
import ai.grakn.concept.Entity;
import ai.grakn.concept.EntityType;
import ai.grakn.concept.Label;
import ai.grakn.concept.RelationshipType;
import ai.grakn.concept.Role;
import ai.grakn.exception.GraqlQueryException;
import ai.grakn.exception.InvalidKBException;
import ai.grakn.graql.Graql;
import ai.grakn.graql.answer.ConceptSet;
import ai.grakn.test.rule.ConcurrentGraknServer;
import ai.grakn.util.Schema;
import com.google.common.collect.Sets;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static ai.grakn.util.GraqlSyntax.Compute.Algorithm.CONNECTED_COMPONENT;
import static ai.grakn.util.GraqlSyntax.Compute.Argument.contains;
import static ai.grakn.util.GraqlSyntax.Compute.Argument.size;
import static ai.grakn.util.GraqlSyntax.Compute.Method.CLUSTER;
import static ai.grakn.util.GraqlSyntax.Compute.Method.COUNT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("CheckReturnValue")
public class ConnectedComponentIT {
    private static final String thing = "thingy";
    private static final String anotherThing = "anotherThing";
    private static final String related = "related";

    private static final String resourceType1 = "resourceType1";
    private static final String resourceType2 = "resourceType2";
    private static final String resourceType3 = "resourceType3";
    private static final String resourceType4 = "resourceType4";
    private static final String resourceType5 = "resourceType5";
    private static final String resourceType6 = "resourceType6";
    private static final String resourceType7 = "resourceType7";

    private ConceptId entityId1;
    private ConceptId entityId2;
    private ConceptId entityId3;
    private ConceptId entityId4;
    private ConceptId aDisconnectedAttribute;

    public GraknSession session;

    @ClassRule
    public static final ConcurrentGraknServer server = new ConcurrentGraknServer();

    @Before
    public void setUp() {
        session = server.sessionWithNewKeyspace();
    }

    @AfterClass
    public static void classTearDown() throws Exception {
        server.cleanup();
    }

    @Test
    public void testNullSourceIdIsIgnored() {
        try (GraknTx tx = session.transaction(GraknTxType.READ)) {
            tx.graql().compute(CLUSTER).using(CONNECTED_COMPONENT).where(contains(null)).execute();
        }

        addSchemaAndEntities();
        try (GraknTx tx = session.transaction(GraknTxType.READ)) {
            tx.graql().compute(CLUSTER).using(CONNECTED_COMPONENT).in(thing).where(contains(null)).execute();
        }
    }

    @Test(expected = GraqlQueryException.class)
    public void testSourceDoesNotExistInSubGraph() {
        addSchemaAndEntities();
        try (GraknTx tx = session.transaction(GraknTxType.READ)) {
            tx.graql().compute(CLUSTER).using(CONNECTED_COMPONENT).in(thing).where(contains(entityId4)).execute();
        }
    }

    @Test
    public void testConnectedComponentOnEmptyGraph() {
        try (GraknTx tx = session.transaction(GraknTxType.WRITE)) {
            // test on an empty rule.tx()
            List<ConceptSet> clusterList = Graql.compute(CLUSTER).withTx(tx).using(CONNECTED_COMPONENT).includeAttributes(true).execute();
            assertTrue(clusterList.isEmpty());
            clusterList = tx.graql().compute(CLUSTER).using(CONNECTED_COMPONENT).execute();
            assertTrue(clusterList.isEmpty());
            assertEquals(0, tx.graql().compute(COUNT).execute().get(0).number().intValue());
        }
    }

    @Test
    public void testConnectedComponentSize() {
        List<ConceptSet> clusterList;

        addSchemaAndEntities();

        try (GraknTx tx = session.transaction(GraknTxType.WRITE)) {
            clusterList = Graql.compute(CLUSTER).withTx(tx).using(CONNECTED_COMPONENT).includeAttributes(true).where(size(1L)).execute();
            assertEquals(0, clusterList.size());

            clusterList = tx.graql().compute(CLUSTER).using(CONNECTED_COMPONENT).where(size(1L)).execute();
            assertEquals(0, clusterList.size());
        }

        addResourceRelations();

        try (GraknTx tx = session.transaction(GraknTxType.READ)) {
            clusterList = tx.graql().compute(CLUSTER).using(CONNECTED_COMPONENT).includeAttributes(true).where(size(1L)).execute();
            assertEquals(5, clusterList.size());

            clusterList = tx.graql().compute(CLUSTER).using(CONNECTED_COMPONENT).includeAttributes(true).where(size(1L), contains(entityId1)).execute();
            assertEquals(0, clusterList.size());

            clusterList = tx.graql().compute(CLUSTER).using(CONNECTED_COMPONENT).where(size(1L)).execute();
            assertEquals(0, clusterList.size());

            clusterList = tx.graql().compute(CLUSTER).using(CONNECTED_COMPONENT).where(contains(entityId4), size(1L)).execute();
            assertEquals(0, clusterList.size());
        }
    }

    @Test
    public void testConnectedComponentImplicitType() {
        String aResourceTypeLabel = "aResourceTypeLabel";

        addSchemaAndEntities();
        addResourceRelations();

        try (GraknTx tx = session.transaction(GraknTxType.WRITE)) {
            AttributeType<String> attributeType =
                    tx.putAttributeType(aResourceTypeLabel, AttributeType.DataType.STRING);
            tx.getEntityType(thing).has(attributeType);
            tx.getEntityType(anotherThing).has(attributeType);
            Attribute aAttribute = attributeType.create("blah");
            tx.getEntityType(thing).instances().forEach(instance -> instance.has(aAttribute));
            tx.getEntityType(anotherThing).instances().forEach(instance -> instance.has(aAttribute));
            tx.commit();
        }

        try (GraknTx tx = session.transaction(GraknTxType.READ)) {
            List<ConceptSet> clusterList = tx.graql().compute(CLUSTER).using(CONNECTED_COMPONENT)
                    .in(thing, anotherThing, aResourceTypeLabel, Schema.ImplicitType.HAS.getLabel(aResourceTypeLabel).getValue()).execute();
            assertEquals(1, clusterList.size());
            assertEquals(5, clusterList.iterator().next().set().size());

            clusterList = tx.graql().compute(CLUSTER).using(CONNECTED_COMPONENT)
                    .in(thing, anotherThing, aResourceTypeLabel, Schema.ImplicitType.HAS.getLabel(aResourceTypeLabel).getValue())
                    .where(contains(entityId2)).execute();
            assertEquals(1, clusterList.size());
            assertEquals(5, clusterList.iterator().next().set().size());

            assertEquals(1, tx.graql().compute(CLUSTER).using(CONNECTED_COMPONENT).includeAttributes(true)
                    .in(thing, anotherThing, aResourceTypeLabel, Schema.ImplicitType.HAS.getLabel(aResourceTypeLabel).getValue())
                    .includeAttributes(true).execute().size());
        }
    }

    @Test
    public void testConnectedComponent() {
        List<ConceptSet> clusterList;

        addSchemaAndEntities();

        try (GraknTx tx = session.transaction(GraknTxType.READ)) {
            clusterList = Graql.compute(CLUSTER).withTx(tx).using(CONNECTED_COMPONENT).includeAttributes(true).execute();
            assertEquals(1, clusterList.size());
            assertEquals(7, clusterList.iterator().next().set().size()); // 4 entities, 3 assertions

            clusterList = Graql.compute(CLUSTER).withTx(tx).using(CONNECTED_COMPONENT).where(contains(entityId1)).includeAttributes(true).execute();
            assertEquals(1, clusterList.size());
            assertEquals(7, clusterList.iterator().next().set().size());

            clusterList = Graql.compute(CLUSTER).withTx(tx).using(CONNECTED_COMPONENT).execute();
            assertEquals(1, clusterList.size());
            assertEquals(7, clusterList.iterator().next().set().size());

            clusterList = Graql.compute(CLUSTER).withTx(tx).using(CONNECTED_COMPONENT).where(contains(entityId4)).execute();
            assertEquals(1, clusterList.size());
            assertEquals(7, clusterList.iterator().next().set().size());
        }

        // add different resources. This may change existing cluster labels.
        addResourceRelations();

        try (GraknTx tx = session.transaction(GraknTxType.READ)) {
            clusterList = tx.graql().compute(CLUSTER).using(CONNECTED_COMPONENT).includeAttributes(true).execute();
            Map<Integer, Integer> populationCount00 = new HashMap<>();
            clusterList.forEach(cluster -> populationCount00.put(cluster.set().size(),
                    populationCount00.containsKey(cluster.set().size()) ? populationCount00.get(cluster.set().size()) + 1 : 1));
            // 5 resources are not connected to anything
            assertEquals(5, populationCount00.get(1).intValue());
            assertEquals(1, populationCount00.get(15).intValue());

            clusterList = tx.graql().compute(CLUSTER).using(CONNECTED_COMPONENT).where(contains(aDisconnectedAttribute)).includeAttributes(true).execute();
            assertEquals(1, clusterList.size());

            clusterList = tx.graql().compute(CLUSTER).using(CONNECTED_COMPONENT).execute();
            assertEquals(1, clusterList.size());
            Map<Integer, Integer> populationCount1 = new HashMap<>();
            clusterList.forEach(cluster -> populationCount1.put(cluster.set().size(),
                    populationCount1.containsKey(cluster.set().size()) ? populationCount1.get(cluster.set().size()) + 1 : 1));
            assertEquals(1, populationCount1.get(7).intValue());

            // test on subtypes. This will change existing cluster labels.
            Set<Label> subTypes = Sets.newHashSet(thing, anotherThing, resourceType1, resourceType2,
                    resourceType3, resourceType4, resourceType5, resourceType6)
                    .stream().map(Label::of).collect(Collectors.toSet());
            clusterList = tx.graql().compute(CLUSTER).using(CONNECTED_COMPONENT).in(subTypes).execute();
            assertEquals(17, clusterList.size()); // No relationships, so this is the entity count;
        }
    }

    @Test
    public void testConnectedComponentConcurrency() {
        addSchemaAndEntities();
        addResourceRelations();

        List<Long> list = new ArrayList<>(4);
        long workerNumber = 4L;
        for (long i = 0L; i < workerNumber; i++) {
            list.add(i);
        }

        Set<List<ConceptSet>> result = list.parallelStream().map(i -> {
            try (GraknTx tx = session.transaction(GraknTxType.READ)) {
                return Graql.compute(CLUSTER).withTx(tx).using(CONNECTED_COMPONENT).execute();
            }
        }).collect(Collectors.toSet());
        result.forEach(map -> {
            assertEquals(1, map.size());
            assertEquals(7L, map.iterator().next().set().size());
        });
    }

    private void addSchemaAndEntities() throws InvalidKBException {
        try (GraknTx tx = session.transaction(GraknTxType.WRITE)) {

            EntityType entityType1 = tx.putEntityType(thing);
            EntityType entityType2 = tx.putEntityType(anotherThing);

            Entity entity1 = entityType1.create();
            Entity entity2 = entityType1.create();
            Entity entity3 = entityType1.create();
            Entity entity4 = entityType2.create();
            entityId1 = entity1.id();
            entityId2 = entity2.id();
            entityId3 = entity3.id();
            entityId4 = entity4.id();

            Role role1 = tx.putRole("role1");
            Role role2 = tx.putRole("role2");
            entityType1.plays(role1).plays(role2);
            entityType2.plays(role1).plays(role2);
            RelationshipType relationshipType = tx.putRelationshipType(related).relates(role1).relates(role2);

            relationshipType.create()
                    .assign(role1, entity1)
                    .assign(role2, entity2).id();
            relationshipType.create()
                    .assign(role1, entity2)
                    .assign(role2, entity3).id();
            relationshipType.create()
                    .assign(role1, entity2)
                    .assign(role2, entity4).id();

            List<AttributeType> attributeTypeList = new ArrayList<>();
            attributeTypeList.add(tx.putAttributeType(resourceType1, AttributeType.DataType.DOUBLE));
            attributeTypeList.add(tx.putAttributeType(resourceType2, AttributeType.DataType.LONG));
            attributeTypeList.add(tx.putAttributeType(resourceType3, AttributeType.DataType.LONG));
            attributeTypeList.add(tx.putAttributeType(resourceType4, AttributeType.DataType.STRING));
            attributeTypeList.add(tx.putAttributeType(resourceType5, AttributeType.DataType.LONG));
            attributeTypeList.add(tx.putAttributeType(resourceType6, AttributeType.DataType.DOUBLE));
            attributeTypeList.add(tx.putAttributeType(resourceType7, AttributeType.DataType.DOUBLE));

            attributeTypeList.forEach(attributeType -> {
                entityType1.has(attributeType);
                entityType2.has(attributeType);
            });

            tx.commit();
        }
    }

    private void addResourceRelations() throws InvalidKBException {
        try (GraknTx tx = session.transaction(GraknTxType.WRITE)) {

            Entity entity1 = tx.getConcept(entityId1);
            Entity entity2 = tx.getConcept(entityId2);
            Entity entity3 = tx.getConcept(entityId3);
            Entity entity4 = tx.getConcept(entityId4);

            entity1.has(tx.getAttributeType(resourceType1).create(1.2))
                    .has(tx.getAttributeType(resourceType1).create(1.5))
                    .has(tx.getAttributeType(resourceType2).create(4L))
                    .has(tx.getAttributeType(resourceType2).create(-1L))
                    .has(tx.getAttributeType(resourceType5).create(-7L))
                    .has(tx.getAttributeType(resourceType6).create(7.5));

            entity2.has(tx.getAttributeType(resourceType5).create(-7L))
                    .has(tx.getAttributeType(resourceType6).create(7.5));

            entity3.has(tx.getAttributeType(resourceType1).create(1.8));

            entity4.has(tx.getAttributeType(resourceType2).create(0L))
                    .has(tx.getAttributeType(resourceType5).create(-7L))
                    .has(tx.getAttributeType(resourceType6).create(7.5));

            // some resources in, but not connect them to any instances
            aDisconnectedAttribute = tx.getAttributeType(resourceType1).create(2.8).id();
            tx.getAttributeType(resourceType2).create(-5L);
            tx.getAttributeType(resourceType3).create(100L);
            tx.getAttributeType(resourceType5).create(10L);
            tx.getAttributeType(resourceType6).create(0.8);

            tx.commit();
        }
    }
}