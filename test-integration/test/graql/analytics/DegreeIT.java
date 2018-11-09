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
import ai.grakn.concept.Relationship;
import ai.grakn.concept.RelationshipType;
import ai.grakn.concept.Role;
import ai.grakn.exception.InvalidKBException;
import ai.grakn.graql.answer.ConceptSetMeasure;
import ai.grakn.test.rule.ConcurrentGraknServer;
import com.google.common.collect.Sets;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static ai.grakn.util.GraqlSyntax.Compute.Algorithm.DEGREE;
import static ai.grakn.util.GraqlSyntax.Compute.Method.CENTRALITY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("CheckReturnValue")
public class DegreeIT {

    public GraknSession session;
    private GraknTx tx;

    @ClassRule
    public static final ConcurrentGraknServer server = new ConcurrentGraknServer();

    @Before
    public void setUp() {
        session = server.sessionWithNewKeyspace();
        tx = session.transaction(GraknTxType.WRITE);
    }

    @AfterClass
    public static void classTearDown() throws Exception {
        server.cleanup();
    }

    @Test
    public void testDegreesSimple() {
        // create instances
        EntityType thingy = tx.putEntityType("thingy");
        EntityType anotherThing = tx.putEntityType("another");

        ConceptId entity1 = thingy.create().id();
        ConceptId entity2 = thingy.create().id();
        ConceptId entity3 = thingy.create().id();
        ConceptId entity4 = anotherThing.create().id();

        Role role1 = tx.putRole("role1");
        Role role2 = tx.putRole("role2");
        thingy.plays(role1).plays(role2);
        anotherThing.plays(role1).plays(role2);
        RelationshipType related = tx.putRelationshipType("related").relates(role1).relates(role2);

        // relate them
        related.create()
                .assign(role1, tx.getConcept(entity1))
                .assign(role2, tx.getConcept(entity2));
        related.create()
                .assign(role1, tx.getConcept(entity2))
                .assign(role2, tx.getConcept(entity3));
        related.create()
                .assign(role1, tx.getConcept(entity2))
                .assign(role2, tx.getConcept(entity4));
        tx.commit();

        tx = session.transaction(GraknTxType.READ);

        Map<ConceptId, Long> correctDegrees = new HashMap<>();
        correctDegrees.put(entity1, 1L);
        correctDegrees.put(entity2, 3L);
        correctDegrees.put(entity3, 1L);
        correctDegrees.put(entity4, 1L);

        // compute degrees
        List<Long> list = new ArrayList<>(4);
        long workerNumber = 4L;
        for (long i = 0L; i < workerNumber; i++) {
            list.add(i);
        }
        tx.close();

        Set<List<ConceptSetMeasure>> result = list.parallelStream().map(i -> {
            try (GraknTx tx = session.transaction(GraknTxType.READ)) {
                return tx.graql().compute(CENTRALITY).using(DEGREE).execute();
            }
        }).collect(Collectors.toSet());
        assertEquals(1, result.size());
        List<ConceptSetMeasure> degrees0 = result.iterator().next();
        assertEquals(2, degrees0.size());
        degrees0.forEach(conceptSetMeasure -> conceptSetMeasure.set().forEach(
                id -> {
                    assertTrue(correctDegrees.containsKey(id));
                    assertEquals(correctDegrees.get(id).longValue(), conceptSetMeasure.measurement().longValue());
                }
        ));

        try (GraknTx tx = session.transaction(GraknTxType.READ)) {
            List<ConceptSetMeasure> degrees1 = tx.graql().compute(CENTRALITY).using(DEGREE).of("thingy").execute();

            assertEquals(2, degrees1.size());

            assertEquals(2, degrees1.get(0).set().size());
            assertEquals(1, degrees1.get(0).measurement().intValue());

            assertEquals(1, degrees1.get(1).set().size());
            assertEquals(3, degrees1.get(1).measurement().intValue());

            degrees1.forEach(conceptSetMeasure -> conceptSetMeasure.set().forEach(
                    id -> {
                        assertTrue(correctDegrees.containsKey(id));
                        assertEquals(correctDegrees.get(id).longValue(), conceptSetMeasure.measurement().longValue());
                    }
            ));

            List<ConceptSetMeasure> degrees2 = tx.graql().compute(CENTRALITY).using(DEGREE).of("thingy", "related").execute();
            assertTrue(degrees1.containsAll(degrees2));

            degrees2 = tx.graql().compute(CENTRALITY).using(DEGREE).execute();
            assertTrue(degrees0.containsAll(degrees2));

            // compute degrees on subgraph
            List<ConceptSetMeasure> degrees3 = tx.graql().compute(CENTRALITY).using(DEGREE).in("thingy", "related").execute();
            assertTrue(degrees1.containsAll(degrees3));

            degrees3 = tx.graql().compute(CENTRALITY).using(DEGREE).of("thingy").in("related").execute();
            assertTrue(degrees1.containsAll(degrees3));
        }
    }

    @Test
    public void testSubIsAccountedForInSubgraph() {
        Role pet = tx.putRole("pet");
        Role owner = tx.putRole("owner");

        Entity person = tx.putEntityType("person").plays(owner).create();

        EntityType animal = tx.putEntityType("animal").plays(pet);
        Entity dog = tx.putEntityType("dog").sup(animal).create();

        tx.putRelationshipType("mans-best-friend").relates(pet).relates(owner)
                .create().assign(pet, dog).assign(owner, person);

        List<ConceptSetMeasure> correctDegrees = new ArrayList<>();
        correctDegrees.add(new ConceptSetMeasure(Sets.newHashSet(person.id(), dog.id()), 1));

        tx.commit();

        try (GraknTx tx = session.transaction(GraknTxType.READ)) {
            // set subgraph, use animal instead of dog
            Set<Label> ct = Sets.newHashSet(Label.of("person"), Label.of("animal"),
                    Label.of("mans-best-friend"));
            List<ConceptSetMeasure> degrees = tx.graql().compute(CENTRALITY).using(DEGREE).in(ct).execute();
            // check that dog has a degree to confirm sub has been inferred
            assertTrue(correctDegrees.containsAll(degrees));
        }
    }

    @Test
    public void testDegreeTwoAttributes() throws InvalidKBException {
        // create a simple tx
        Role pet = tx.putRole("pet");
        Role owner = tx.putRole("owner");
        RelationshipType mansBestFriend = tx.putRelationshipType("mans-best-friend").relates(pet).relates(owner);

        EntityType person = tx.putEntityType("person").plays(owner);
        EntityType animal = tx.putEntityType("animal").plays(pet);
        AttributeType<String> name = tx.putAttributeType("name", AttributeType.DataType.STRING);
        AttributeType<String> altName =
                tx.putAttributeType("alternate-name", AttributeType.DataType.STRING);

        animal.has(name).has(altName);

        // add data to the graph
        Entity coco = animal.create();
        Entity dave = person.create();
        Attribute coconut = name.create("coconut");
        Attribute stinky = altName.create("stinky");
        mansBestFriend.create().assign(owner, dave).assign(pet, coco);
        coco.has(coconut).has(stinky);

        // manually compute the degree for small graph
        List<ConceptSetMeasure> subgraphReferenceDegrees = new ArrayList<>();
        subgraphReferenceDegrees.add(new ConceptSetMeasure(Sets.newHashSet(coco.id(), dave.id()), 1));

        // manually compute degree for almost full graph
        List<ConceptSetMeasure> almostFullReferenceDegrees = new ArrayList<>();
        almostFullReferenceDegrees.add(new ConceptSetMeasure(Sets.newHashSet(coco.id()), 2));
        almostFullReferenceDegrees.add(new ConceptSetMeasure(Sets.newHashSet(dave.id(), coconut.id()), 1));

        // manually compute degrees
        List<ConceptSetMeasure> fullReferenceDegrees = new ArrayList<>();
        fullReferenceDegrees.add(new ConceptSetMeasure(Sets.newHashSet(coco.id()), 3));
        fullReferenceDegrees.add(new ConceptSetMeasure(Sets.newHashSet(dave.id(), coconut.id(), stinky.id()), 1));

        tx.commit();

        try (GraknTx tx = session.transaction(GraknTxType.READ)) {

            // create a subgraph excluding attributes and their relationship
            HashSet<Label> subGraphTypes = Sets.newHashSet(Label.of("animal"), Label.of("person"),
                    Label.of("mans-best-friend"));
            List<ConceptSetMeasure> degrees = tx.graql().compute(CENTRALITY).using(DEGREE)
                    .in(subGraphTypes).execute();
            assertTrue(subgraphReferenceDegrees.containsAll(degrees));

            // create a subgraph excluding one attribute type only
            HashSet<Label> almostFullTypes = Sets.newHashSet(Label.of("animal"), Label.of("person"),
                    Label.of("mans-best-friend"), Label.of("@has-name"), Label.of("name"));
            degrees = tx.graql().compute(CENTRALITY).using(DEGREE).in(almostFullTypes).execute();
            assertTrue(almostFullReferenceDegrees.containsAll(degrees));

            // full graph
            degrees = tx.graql().compute(CENTRALITY).using(DEGREE).execute();
            assertTrue(fullReferenceDegrees.containsAll(degrees));
        }
    }

    @Test
    public void testDegreeMissingRolePlayer() {
        Role pet = tx.putRole("pet");
        Role owner = tx.putRole("owner");
        Role breeder = tx.putRole("breeder");
        RelationshipType mansBestFriend = tx.putRelationshipType("mans-best-friend")
                .relates(pet).relates(owner).relates(breeder);
        EntityType person = tx.putEntityType("person").plays(owner).plays(breeder);
        EntityType animal = tx.putEntityType("animal").plays(pet);

        // make one person breeder and owner
        Entity coco = animal.create();
        Entity dave = person.create();
        mansBestFriend.create().assign(pet, coco).assign(owner, dave);

        // manual degrees
        List<ConceptSetMeasure> referenceDegrees = new ArrayList<>();
        referenceDegrees.add(new ConceptSetMeasure(Sets.newHashSet(coco.id(), dave.id()), 1));

        tx.commit();

        try (GraknTx tx = session.transaction(GraknTxType.READ)) {
            List<ConceptSetMeasure> degrees = tx.graql().compute(CENTRALITY).using(DEGREE).execute();
            assertTrue(referenceDegrees.containsAll(degrees));
        }
    }

    @Test
    public void testRelationshipPlaysARole() throws InvalidKBException {

        Role pet = tx.putRole("pet");
        Role owner = tx.putRole("owner");
        RelationshipType mansBestFriend = tx.putRelationshipType("mans-best-friend").relates(pet).relates(owner);

        EntityType person = tx.putEntityType("person").plays(owner);
        EntityType animal = tx.putEntityType("animal").plays(pet);

        Role ownership = tx.putRole("ownership");
        Role ownershipResource = tx.putRole("ownership-resource");
        RelationshipType hasOwnershipResource = tx.putRelationshipType("has-ownership-resource")
                .relates(ownership).relates(ownershipResource);

        AttributeType<String> startDate = tx.putAttributeType("start-date", AttributeType.DataType.STRING);
        startDate.plays(ownershipResource);
        mansBestFriend.plays(ownership);

        // add instances
        Entity coco = animal.create();
        Entity dave = person.create();
        Relationship daveOwnsCoco = mansBestFriend.create()
                .assign(owner, dave).assign(pet, coco);
        Attribute aStartDate = startDate.create("01/01/01");
        hasOwnershipResource.create()
                .assign(ownershipResource, aStartDate).assign(ownership, daveOwnsCoco);

        List<ConceptSetMeasure> referenceDegrees = new ArrayList<>();
        referenceDegrees.add(new ConceptSetMeasure(Sets.newHashSet(coco.id(), dave.id(), aStartDate.id(), daveOwnsCoco.id()), 1));

        tx.commit();

        try (GraknTx tx = session.transaction(GraknTxType.READ)) {
            List<ConceptSetMeasure> degrees = tx.graql().compute(CENTRALITY).using(DEGREE).execute();
            assertTrue(referenceDegrees.containsAll(degrees));
        }
    }

    @Test
    public void testDegreeTernaryRelationships() throws InvalidKBException {
        // make relationship
        Role productionWithCast = tx.putRole("production-with-cast");
        Role actor = tx.putRole("actor");
        Role characterBeingPlayed = tx.putRole("character-being-played");
        RelationshipType hasCast = tx.putRelationshipType("has-cast")
                .relates(productionWithCast)
                .relates(actor)
                .relates(characterBeingPlayed);

        EntityType movie = tx.putEntityType("movie").plays(productionWithCast);
        EntityType person = tx.putEntityType("person").plays(actor);
        EntityType character = tx.putEntityType("character").plays(characterBeingPlayed);

        Entity godfather = movie.create();
        Entity marlonBrando = person.create();
        Entity donVitoCorleone = character.create();

        hasCast.create()
                .assign(productionWithCast, godfather)
                .assign(actor, marlonBrando)
                .assign(characterBeingPlayed, donVitoCorleone);

        List<ConceptSetMeasure> referenceDegrees = new ArrayList<>();
        referenceDegrees.add(new ConceptSetMeasure(Sets.newHashSet(godfather.id(), marlonBrando.id(), donVitoCorleone.id()), 1));

        tx.commit();

        try (GraknTx tx = session.transaction(GraknTxType.READ)) {
            List<ConceptSetMeasure> degrees = tx.graql().compute(CENTRALITY).using(DEGREE).execute();
            assertTrue(referenceDegrees.containsAll(degrees));
        }
    }

    @Test
    public void testOneRolePlayerMultipleRoles() throws InvalidKBException {

        Role pet = tx.putRole("pet");
        Role owner = tx.putRole("owner");
        Role breeder = tx.putRole("breeder");
        RelationshipType mansBestFriend = tx.putRelationshipType("mans-best-friend")
                .relates(pet).relates(owner).relates(breeder);
        EntityType person = tx.putEntityType("person").plays(owner).plays(breeder);
        EntityType animal = tx.putEntityType("animal").plays(pet);

        // make one person breeder and owner
        Entity coco = animal.create();
        Entity dave = person.create();

        mansBestFriend.create()
                .assign(pet, coco)
                .assign(owner, dave)
                .assign(breeder, dave);

        List<ConceptSetMeasure> referenceDegrees = new ArrayList<>();
        referenceDegrees.add(new ConceptSetMeasure(Sets.newHashSet(coco.id()), 1));
        referenceDegrees.add(new ConceptSetMeasure(Collections.singleton(dave.id()), 2));

        tx.commit();

        try (GraknTx tx = session.transaction(GraknTxType.READ)) {
            List<ConceptSetMeasure> degrees = tx.graql().compute(CENTRALITY).using(DEGREE).execute();
            assertTrue(referenceDegrees.containsAll(degrees));
        }
    }
}