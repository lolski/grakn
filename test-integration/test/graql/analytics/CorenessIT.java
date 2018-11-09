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
import ai.grakn.concept.RelationshipType;
import ai.grakn.concept.Role;
import ai.grakn.exception.GraqlQueryException;
import ai.grakn.exception.InvalidKBException;
import ai.grakn.graql.Graql;
import ai.grakn.graql.answer.ConceptSetMeasure;
import ai.grakn.test.rule.ConcurrentGraknServer;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static ai.grakn.util.GraqlSyntax.Compute.Algorithm.K_CORE;
import static ai.grakn.util.GraqlSyntax.Compute.Argument.min_k;
import static ai.grakn.util.GraqlSyntax.Compute.Method.CENTRALITY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("CheckReturnValue")
public class CorenessIT {
    private static final String thing = "thingy";
    private static final String anotherThing = "anotherThing";
    private static final String related = "related";
    private static final String veryRelated = "veryRelated";

    private ConceptId entityId1;
    private ConceptId entityId2;
    private ConceptId entityId3;
    private ConceptId entityId4;

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

    @Test(expected = GraqlQueryException.class)
    public void testKSmallerThan2_ThrowsException() {
        try (GraknTx tx = session.transaction(GraknTxType.READ)) {
            tx.graql().compute(CENTRALITY).using(K_CORE).where(min_k(1)).execute();
        }
    }

    @Test
    public void testOnEmptyGraph_ReturnsEmptyMap() {
        try (GraknTx tx = session.transaction(GraknTxType.READ)) {
            List<ConceptSetMeasure> result = tx.graql().compute(CENTRALITY).using(K_CORE).execute();
            assertTrue(result.isEmpty());
        }
    }

    @Test
    public void testOnGraphWithoutRelationships_ReturnsEmptyMap() {
        try (GraknTx tx = session.transaction(GraknTxType.WRITE)) {
            tx.putEntityType(thing).create();
            tx.putEntityType(anotherThing).create();
            List<ConceptSetMeasure> result = tx.graql().compute(CENTRALITY).using(K_CORE).execute();
            assertTrue(result.isEmpty());
        }
    }

    @Test
    public void testOnGraphWithTwoEntitiesAndTwoRelationships() {
        try (GraknTx tx = session.transaction(GraknTxType.WRITE)) {
            EntityType entityType = tx.putEntityType(thing);
            Entity entity1 = entityType.create();
            Entity entity2 = entityType.create();

            Role role1 = tx.putRole("role1");
            Role role2 = tx.putRole("role2");
            entityType.plays(role1).plays(role2);
            tx.putRelationshipType(related)
                    .relates(role1).relates(role2)
                    .create()
                    .assign(role1, entity1)
                    .assign(role2, entity2);

            Role role3 = tx.putRole("role3");
            Role role4 = tx.putRole("role4");
            entityType.plays(role3).plays(role4);
            tx.putRelationshipType(veryRelated)
                    .relates(role3).relates(role4)
                    .create()
                    .assign(role3, entity1)
                    .assign(role4, entity2);

            List<ConceptSetMeasure> result = tx.graql().compute(CENTRALITY).using(K_CORE).execute();
            assertTrue(result.isEmpty());
        }
    }

    @Test
    public void testOnGraphWithFourEntitiesAndSixRelationships() {
        addSchemaAndEntities();

        try (GraknTx tx = session.transaction(GraknTxType.READ)) {
            List<ConceptSetMeasure> result = tx.graql().compute(CENTRALITY).using(K_CORE).execute();
            assertEquals(1, result.size());
            assertEquals(4, result.get(0).set().size());
            assertEquals(3, result.get(0).measurement().intValue());

            result = tx.graql().compute(CENTRALITY).using(K_CORE).of(thing).execute();
            assertEquals(1, result.size());
            assertEquals(2, result.get(0).set().size());
            assertEquals(3, result.get(0).measurement().intValue());

            result = tx.graql().compute(CENTRALITY).using(K_CORE).in(thing, anotherThing, related).execute();
            assertEquals(1, result.size());
            assertEquals(4, result.get(0).set().size());
            assertEquals(2, result.get(0).measurement().intValue());
        }
    }

    @Test
    public void testImplicitTypeShouldBeIncluded() {
        addSchemaAndEntities();

        try (GraknTx tx = session.transaction(GraknTxType.WRITE)) {
            String aResourceTypeLabel = "aResourceTypeLabel";
            AttributeType<String> attributeType =
                    tx.putAttributeType(aResourceTypeLabel, AttributeType.DataType.STRING);
            tx.getEntityType(thing).has(attributeType);
            tx.getEntityType(anotherThing).has(attributeType);

            Attribute Attribute1 = attributeType.create("blah");
            tx.getConcept(entityId1).asEntity().has(Attribute1);
            tx.getConcept(entityId2).asEntity().has(Attribute1);
            tx.getConcept(entityId3).asEntity().has(Attribute1);
            tx.getConcept(entityId4).asEntity().has(Attribute1);

            Attribute Attribute2 = attributeType.create("bah");
            tx.getConcept(entityId1).asEntity().has(Attribute2);
            tx.getConcept(entityId2).asEntity().has(Attribute2);
            tx.getConcept(entityId3).asEntity().has(Attribute2);

            tx.commit();
        }

        List<ConceptSetMeasure> result;
        try (GraknTx tx = session.transaction(GraknTxType.READ)) {
            result = tx.graql().compute(CENTRALITY).using(K_CORE).execute();
            System.out.println("result = " + result);
            assertEquals(2, result.size());

            assertEquals(1, result.get(0).set().size());
            assertEquals(3, result.get(0).measurement().intValue());

            assertEquals(5, result.get(1).set().size());
            assertEquals(4, result.get(1).measurement().intValue());

            result = tx.graql().compute(CENTRALITY).using(K_CORE).where(min_k(4L)).execute();
            assertEquals(1, result.size());
            assertEquals(5, result.get(0).set().size());
            assertEquals(4, result.get(0).measurement().intValue());
        }
    }

    @Test
    public void testDisconnectedCores() {
        try (GraknTx tx = session.transaction(GraknTxType.WRITE)) {
            EntityType entityType1 = tx.putEntityType(thing);
            EntityType entityType2 = tx.putEntityType(anotherThing);

            Role role1 = tx.putRole("role1");
            Role role2 = tx.putRole("role2");
            RelationshipType relationshipType1 = tx.putRelationshipType(related)
                    .relates(role1).relates(role2);

            Role role3 = tx.putRole("role3");
            Role role4 = tx.putRole("role4");
            RelationshipType relationshipType2 = tx.putRelationshipType(veryRelated)
                    .relates(role3).relates(role4);

            entityType1.plays(role1).plays(role2).plays(role3).plays(role4);
            entityType2.plays(role1).plays(role2).plays(role3).plays(role4);

            Entity entity0 = entityType1.create();
            Entity entity1 = entityType1.create();
            Entity entity2 = entityType1.create();
            Entity entity3 = entityType1.create();
            Entity entity4 = entityType1.create();
            Entity entity5 = entityType1.create();
            Entity entity6 = entityType1.create();
            Entity entity7 = entityType1.create();
            Entity entity8 = entityType1.create();

            relationshipType1.create()
                    .assign(role1, entity1)
                    .assign(role2, entity2);
            relationshipType1.create()
                    .assign(role1, entity2)
                    .assign(role2, entity3);
            relationshipType1.create()
                    .assign(role1, entity3)
                    .assign(role2, entity4);
            relationshipType1.create()
                    .assign(role1, entity1)
                    .assign(role2, entity3);
            relationshipType1.create()
                    .assign(role1, entity1)
                    .assign(role2, entity4);
            relationshipType1.create()
                    .assign(role1, entity2)
                    .assign(role2, entity4);

            relationshipType1.create()
                    .assign(role1, entity5)
                    .assign(role2, entity6);
            relationshipType2.create()
                    .assign(role3, entity5)
                    .assign(role4, entity7);
            relationshipType2.create()
                    .assign(role3, entity5)
                    .assign(role4, entity8);
            relationshipType2.create()
                    .assign(role3, entity6)
                    .assign(role4, entity7);
            relationshipType2.create()
                    .assign(role3, entity6)
                    .assign(role4, entity8);
            relationshipType2.create()
                    .assign(role3, entity7)
                    .assign(role4, entity8);

            relationshipType1.create()
                    .assign(role1, entity0)
                    .assign(role2, entity1);
            relationshipType1.create()
                    .assign(role1, entity0)
                    .assign(role2, entity8);

            tx.commit();
        }

        List<ConceptSetMeasure> result;
        try (GraknTx tx = session.transaction(GraknTxType.READ)) {
            result = tx.graql().compute(CENTRALITY).using(K_CORE).execute();
            assertEquals(2, result.size());

            assertEquals(1, result.get(0).set().size());
            assertEquals(2, result.get(0).measurement().intValue());

            assertEquals(8, result.get(1).set().size());
            assertEquals(3, result.get(1).measurement().intValue());

            result = tx.graql().compute(CENTRALITY).using(K_CORE).where(min_k(3L)).execute();
            assertEquals(1, result.size());
            assertEquals(8, result.get(0).set().size());
            assertEquals(3, result.get(0).measurement().intValue());
        }
    }

    @Test
    public void testConcurrency() {
        addSchemaAndEntities();

        List<Long> list = new ArrayList<>(4);
        long workerNumber = 4L;
        for (long i = 0L; i < workerNumber; i++) {
            list.add(i);
        }

        Set<List<ConceptSetMeasure>> result = list.parallelStream().map(i -> {
            try (GraknTx tx = session.transaction(GraknTxType.READ)) {
                return Graql.compute(CENTRALITY).withTx(tx).using(K_CORE).where(min_k(3L)).execute();
            }
        }).collect(Collectors.toSet());
        assertEquals(1, result.size());
        result.forEach(map -> {
            assertEquals(1, map.size());
            assertEquals(4, map.get(0).set().size());
            assertEquals(3, map.get(0).measurement().intValue());
        });
    }

    private void addSchemaAndEntities() throws InvalidKBException {
        try (GraknTx tx = session.transaction(GraknTxType.WRITE)) {
            EntityType entityType1 = tx.putEntityType(thing);
            EntityType entityType2 = tx.putEntityType(anotherThing);

            Role role1 = tx.putRole("role1");
            Role role2 = tx.putRole("role2");
            RelationshipType relationshipType1 = tx.putRelationshipType(related)
                    .relates(role1).relates(role2);

            Role role3 = tx.putRole("role3");
            Role role4 = tx.putRole("role4");
            RelationshipType relationshipType2 = tx.putRelationshipType(veryRelated)
                    .relates(role3).relates(role4);

            entityType1.plays(role1).plays(role2).plays(role3).plays(role4);
            entityType2.plays(role1).plays(role2).plays(role3).plays(role4);

            Entity entity1 = entityType1.create();
            Entity entity2 = entityType1.create();
            Entity entity3 = entityType2.create();
            Entity entity4 = entityType2.create();

            relationshipType1.create()
                    .assign(role1, entity1)
                    .assign(role2, entity2);
            relationshipType1.create()
                    .assign(role1, entity2)
                    .assign(role2, entity3);
            relationshipType1.create()
                    .assign(role1, entity3)
                    .assign(role2, entity4);
            relationshipType1.create()
                    .assign(role1, entity4)
                    .assign(role2, entity1);

            relationshipType2.create()
                    .assign(role3, entity1)
                    .assign(role4, entity3);
            relationshipType2.create()
                    .assign(role3, entity2)
                    .assign(role4, entity4);

            entityId1 = entity1.id();
            entityId2 = entity2.id();
            entityId3 = entity3.id();
            entityId4 = entity4.id();

            tx.commit();
        }
    }
}