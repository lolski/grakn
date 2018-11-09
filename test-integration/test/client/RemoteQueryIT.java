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

package ai.grakn.test.client;

import ai.grakn.GraknSession;
import ai.grakn.GraknTx;
import ai.grakn.GraknTxType;
import ai.grakn.Keyspace;
import ai.grakn.client.Grakn;
import ai.grakn.concept.Attribute;
import ai.grakn.concept.AttributeType;
import ai.grakn.concept.AttributeType.DataType;
import ai.grakn.concept.Concept;
import ai.grakn.concept.ConceptId;
import ai.grakn.concept.Entity;
import ai.grakn.concept.EntityType;
import ai.grakn.concept.Label;
import ai.grakn.concept.Relationship;
import ai.grakn.concept.RelationshipType;
import ai.grakn.concept.Role;
import ai.grakn.concept.SchemaConcept;
import ai.grakn.concept.Thing;
import ai.grakn.concept.Type;
import ai.grakn.graql.AggregateQuery;
import ai.grakn.graql.DeleteQuery;
import ai.grakn.graql.GetQuery;
import ai.grakn.graql.Pattern;
import ai.grakn.graql.QueryBuilder;
import ai.grakn.graql.Var;
import ai.grakn.graql.admin.ReasonerQuery;
import ai.grakn.graql.answer.AnswerGroup;
import ai.grakn.graql.answer.ConceptList;
import ai.grakn.graql.answer.ConceptMap;
import ai.grakn.graql.answer.ConceptSet;
import ai.grakn.graql.answer.ConceptSetMeasure;
import ai.grakn.graql.answer.Value;
import ai.grakn.graql.internal.printer.Printer;
import ai.grakn.test.rule.ConcurrentGraknServer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Sets;
import junit.framework.TestCase;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static ai.grakn.graql.Graql.count;
import static ai.grakn.graql.Graql.group;
import static ai.grakn.graql.Graql.label;
import static ai.grakn.graql.Graql.max;
import static ai.grakn.graql.Graql.mean;
import static ai.grakn.graql.Graql.median;
import static ai.grakn.graql.Graql.min;
import static ai.grakn.graql.Graql.std;
import static ai.grakn.graql.Graql.sum;
import static ai.grakn.graql.Graql.var;
import static ai.grakn.util.GraqlSyntax.Compute.Algorithm.CONNECTED_COMPONENT;
import static ai.grakn.util.GraqlSyntax.Compute.Algorithm.DEGREE;
import static ai.grakn.util.GraqlSyntax.Compute.Algorithm.K_CORE;
import static ai.grakn.util.GraqlSyntax.Compute.Method.CENTRALITY;
import static ai.grakn.util.GraqlSyntax.Compute.Method.CLUSTER;
import static ai.grakn.util.GraqlSyntax.Compute.Method.COUNT;
import static ai.grakn.util.GraqlSyntax.Compute.Method.MAX;
import static ai.grakn.util.GraqlSyntax.Compute.Method.MEAN;
import static ai.grakn.util.GraqlSyntax.Compute.Method.MEDIAN;
import static ai.grakn.util.GraqlSyntax.Compute.Method.MIN;
import static ai.grakn.util.GraqlSyntax.Compute.Method.PATH;
import static ai.grakn.util.GraqlSyntax.Compute.Method.STD;
import static ai.grakn.util.GraqlSyntax.Compute.Method.SUM;
import static java.util.stream.Collectors.toSet;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * Integration Tests for {@link ai.grakn.core.server.ServerRPC}
 */
@SuppressWarnings("CheckReturnValue")
public class RemoteQueryIT {

    @ClassRule
    public static final ConcurrentGraknServer server = new ConcurrentGraknServer();

    private static GraknSession localSession;
    private static Grakn.Session remoteSession;

    @Rule
    public final ExpectedException exception = ExpectedException.none();

    @Before
    public void setUp() {
        localSession = server.sessionWithNewKeyspace();
        remoteSession = new Grakn(server.grpcUri()).session(localSession.keyspace());
    }

    @After
    public void tearDown() {
        localSession.close(); remoteSession.close();
    }

    @AfterClass
    public static void classTearDown() throws Exception {
        server.cleanup();
    }

    @Test
    public void testOpeningASession_ReturnARemoteGraknSession() {
        try (GraknSession session = new Grakn(server.grpcUri()).session(localSession.keyspace())) {
            assertTrue(Grakn.Session.class.isAssignableFrom(session.getClass()));
        }
    }

    @Test
    public void testOpeningASessionWithAGivenUriAndKeyspace_TheUriAndKeyspaceAreSet() {
        try (GraknSession session = new Grakn(server.grpcUri()).session(localSession.keyspace())) {
            assertEquals(localSession.keyspace(), session.keyspace());
        }
    }

    @Test
    public void testOpeningATransactionFromASession_ReturnATransactionWithParametersSet() {
        try (GraknSession session = new Grakn(server.grpcUri()).session(localSession.keyspace())) {
            try (GraknTx tx = session.transaction(GraknTxType.READ)) {
                assertEquals(session, tx.session());
                assertEquals(localSession.keyspace(), tx.keyspace());
                assertEquals(GraknTxType.READ, tx.txType());
            }
        }
    }

    @Test
    public void testPuttingEntityType_EnsureItIsAdded() {
        String label = "Oliver";
        try (Grakn.Transaction tx = remoteSession.transaction(GraknTxType.WRITE)) {
            tx.putEntityType(label);
            tx.commit();
        }

        try (GraknTx tx = localSession.transaction(GraknTxType.WRITE)) {
            assertNotNull(tx.getEntityType(label));
        }
    }

    @Test
    public void testGettingEntityType_EnsureItIsReturned() {
        String label = "Oliver";
        try (GraknTx tx = localSession.transaction(GraknTxType.WRITE)) {
            tx.putEntityType(label);
            tx.commit();
        }

        try (Grakn.Transaction tx = remoteSession.transaction(GraknTxType.WRITE)) {
            assertNotNull(tx.getEntityType(label));
        }
    }

    @Test
    public void testExecutingAndCommittingAQuery_TheQueryIsCommitted() {
        try (Grakn.Transaction tx = remoteSession.transaction(GraknTxType.WRITE)) {
            tx.graql().define(label("person").sub("entity")).execute();
            tx.commit();
        }

        try (GraknTx tx = localSession.transaction(GraknTxType.READ)) {
            assertNotNull(tx.getEntityType("person"));
        }
    }

    @Test
    public void testExecutingAQueryAndNotCommitting_TheQueryIsNotCommitted() {
        try (Grakn.Transaction tx = remoteSession.transaction(GraknTxType.WRITE)) {
            tx.graql().define(label("flibflab").sub("entity")).execute();
        }

        try (GraknTx tx = localSession.transaction(GraknTxType.READ)) {
            assertNull(tx.getEntityType("flibflab"));
        }
    }

    @Test
    public void testExecutingAQuery_ResultsAreReturned() {
        List<ConceptMap> answers;

        try (Grakn.Transaction tx = remoteSession.transaction(GraknTxType.READ)) {
            answers = tx.graql().match(var("x").sub("thing")).get().execute();
        }

        int size;
        try (GraknTx tx = localSession.transaction(GraknTxType.READ)) {
            size = tx.graql().match(var("x").sub("thing")).get().execute().size();
        }

        assertThat(answers, hasSize(size));

        try (GraknTx tx = localSession.transaction(GraknTxType.READ)) {
            for (ConceptMap answer : answers) {
                assertThat(answer.vars(), contains(var("x")));
                assertNotNull(tx.getConcept(answer.get("x").id()));
            }
        }
    }

    @Test
    @Ignore // TODO: complete with richer relationship structures
    public void testGetQueryForRelationship() {
        try (Grakn.Transaction tx = remoteSession.transaction(GraknTxType.WRITE)) {
            List<ConceptMap> directorships = tx.graql().match(var("x").isa("directed-by")).get().execute();

            for (ConceptMap directorship : directorships) {
                System.out.println(Printer.stringPrinter(true).toString(directorship.get("x")));
            }
        }
    }

    @Test
    @Ignore
    public void testExecutingAQuery_ExplanationsAreReturned() {
        GraknSession reasonerLocalSession = server.sessionWithNewKeyspace();
        try (GraknTx tx = reasonerLocalSession.transaction(GraknTxType.WRITE)) {
//            GenealogyKB.get().accept(tx);
            tx.commit();
        }

        Grakn.Session reasonerRemoteSession = new Grakn(server.grpcUri()).session(reasonerLocalSession.keyspace());

        List<ConceptMap> remoteAnswers;
        List<ConceptMap> localAnswers;

        final long limit = 3;
        String queryString = "match " +
                "($x, $y) isa cousins;" +
                "limit " + limit + ";" +
                "get;";

        try (Grakn.Transaction tx = reasonerRemoteSession.transaction(GraknTxType.READ)) {
            remoteAnswers = tx.graql().infer(true).<GetQuery>parse(queryString).execute();
        }

        try (GraknTx tx = reasonerLocalSession.transaction(GraknTxType.READ)) {
            localAnswers = tx.graql().infer(true).<GetQuery>parse(queryString).execute();
        }

        assertEquals(remoteAnswers.size(), limit);
        remoteAnswers.forEach(answer -> {
            testExplanation(answer);

            String specificQuery = "match " +
                    "$x id '" + answer.get(var("x")).id().getValue() + "';" +
                    "$y id '" + answer.get(var("y")).id().getValue() + "';" +
                    "(cousin: $x, cousin: $y) isa cousins;" +
                    "limit 1; get;";

            ConceptMap specificAnswer;
            try (Grakn.Transaction tx = reasonerRemoteSession.transaction(GraknTxType.READ)) {
                specificAnswer = Iterables.getOnlyElement(tx.graql().infer(true).<GetQuery>parse(specificQuery).execute());
            }
            assertEquals(answer, specificAnswer);
            testExplanation(specificAnswer);
        });
    }

    private void testExplanation(ConceptMap answer) {
        answerHasConsistentExplanations(answer);
        checkExplanationCompleteness(answer);
        checkAnswerConnectedness(answer);
    }

    //ensures that each branch ends up with an lookup explanation
    private void checkExplanationCompleteness(ConceptMap answer) {
        assertFalse("Non-lookup explanation misses children",
                answer.explanations().stream()
                        .filter(e -> !e.isLookupExplanation())
                        .anyMatch(e -> e.getAnswers().isEmpty())
        );
    }

    private void checkAnswerConnectedness(ConceptMap answer) {
        ImmutableList<ConceptMap> answers = answer.explanation().getAnswers();
        answers.forEach(a -> {
            TestCase.assertTrue("Disconnected answer in explanation",
                    answers.stream()
                            .filter(a2 -> !a2.equals(a))
                            .anyMatch(a2 -> !Sets.intersection(a.vars(), a2.vars()).isEmpty())
            );
        });
    }

    private void answerHasConsistentExplanations(ConceptMap answer) {
        Set<ConceptMap> answers = answer.explanation().deductions().stream()
                .filter(a -> !a.explanation().isJoinExplanation())
                .collect(Collectors.toSet());

        answers.forEach(a -> TestCase.assertTrue("Answer has inconsistent explanations", explanationConsistentWithAnswer(a)));
    }

    private boolean explanationConsistentWithAnswer(ConceptMap ans) {
        ReasonerQuery query = ans.explanation().getQuery();
        Set<Var> vars = query != null ? query.getVarNames() : new HashSet<>();
        return vars.containsAll(ans.map().keySet());
    }

    @Test
    public void testExecutingTwoSequentialQueries_ResultsAreTheSame() {
        Set<ConceptMap> answers1;
        Set<ConceptMap> answers2;

        try (Grakn.Transaction tx = remoteSession.transaction(GraknTxType.READ)) {
            answers1 = tx.graql().match(var("x").sub("thing")).get().stream().collect(toSet());
            answers2 = tx.graql().match(var("x").sub("thing")).get().stream().collect(toSet());
        }

        assertEquals(answers1, answers2);
    }

    @Test
    public void testExecutingTwoParallelQueries_GetBothResults() {
        try (Grakn.Transaction tx = remoteSession.transaction(GraknTxType.READ)) {
            GetQuery query = tx.graql().match(var("x").sub("thing")).get();

            Iterator<ConceptMap> iterator1 = query.iterator();
            Iterator<ConceptMap> iterator2 = query.iterator();

            while (iterator1.hasNext() || iterator2.hasNext()) {
                assertEquals(iterator1.next(), iterator2.next());
                assertEquals(iterator1.hasNext(), iterator2.hasNext());
            }
        }
    }


    @Test
    public void testGettingAConcept_TheInformationOnTheConceptIsCorrect() {
        try (GraknTx remoteTx = remoteSession.transaction(GraknTxType.READ);
             GraknTx localTx = localSession.transaction(GraknTxType.READ)
        ) {
            GetQuery query = remoteTx.graql().match(var("x")).get();

            for (ConceptMap answer : query) {
                Concept remoteConcept = answer.get("x");
                Concept localConcept = localTx.getConcept(remoteConcept.id());

                assertEquals(localConcept.isAttribute(), remoteConcept.isAttribute());
                assertEquals(localConcept.isAttributeType(), remoteConcept.isAttributeType());
                assertEquals(localConcept.isEntity(), remoteConcept.isEntity());
                assertEquals(localConcept.isEntityType(), remoteConcept.isEntityType());
                assertEquals(localConcept.isRelationship(), remoteConcept.isRelationship());
                assertEquals(localConcept.isRelationshipType(), remoteConcept.isRelationshipType());
                assertEquals(localConcept.isRole(), remoteConcept.isRole());
                assertEquals(localConcept.isRule(), remoteConcept.isRule());
                assertEquals(localConcept.isSchemaConcept(), remoteConcept.isSchemaConcept());
                assertEquals(localConcept.isThing(), remoteConcept.isThing());
                assertEquals(localConcept.isType(), remoteConcept.isType());
                assertEquals(localConcept.id(), remoteConcept.id());
                assertEquals(localConcept.isDeleted(), remoteConcept.isDeleted());
                assertEquals(localConcept.keyspace(), remoteConcept.keyspace());
            }
        }
    }

    @Test
    public void testExecutingDeleteQueries_ConceptsAreDeleted() {
        try (GraknTx tx = localSession.transaction(GraknTxType.WRITE)) {
            EntityType person = tx.putEntityType("person");
            AttributeType name = tx.putAttributeType("name", DataType.STRING);
            AttributeType email = tx.putAttributeType("email", DataType.STRING);
            Role actor = tx.putRole("actor");
            Role characterBeingPlayed = tx.putRole("character-being-played");
            RelationshipType hasCast = tx.putRelationshipType("has-cast").relates(actor).relates(characterBeingPlayed);
            person.key(email).has(name);
            person.plays(actor).plays(characterBeingPlayed);

            Entity marco = person.create().has(name.create("marco")).has(email.create("marco@yolo.com"));
            Entity luca = person.create().has(name.create("luca")).has(email.create("luca@yolo.com"));
            hasCast.create().assign(actor, marco).assign(characterBeingPlayed, luca);
            tx.commit();
        }
        try (Grakn.Transaction tx = remoteSession.transaction(GraknTxType.WRITE)) {
            DeleteQuery deleteQuery = tx.graql().match(var("g").rel("x").rel("y").isa("has-cast")).delete("x", "y");
            deleteQuery.execute();
            assertTrue(tx.graql().match(var().rel("x").rel("y").isa("has-cast")).get("x", "y").execute().isEmpty());

            deleteQuery = tx.graql().match(var("x").isa("person")).delete();
            deleteQuery.execute();
            assertTrue(tx.graql().match(var("x").isa("person")).get().execute().isEmpty());
        }
    }



    @Test
    public void testGettingARelationship_TheInformationOnTheRelationshipIsCorrect() {
        try (GraknTx tx = localSession.transaction(GraknTxType.WRITE)) {
            EntityType person = tx.putEntityType("person");
            AttributeType name = tx.putAttributeType("name", DataType.STRING);
            AttributeType email = tx.putAttributeType("email", DataType.STRING);
            Role actor = tx.putRole("actor");
            Role characterBeingPlayed = tx.putRole("character-being-played");
            RelationshipType hasCast = tx.putRelationshipType("has-cast").relates(actor).relates(characterBeingPlayed);
            person.key(email).has(name);
            person.plays(actor).plays(characterBeingPlayed);

            Entity marco = person.create().has(name.create("marco")).has(email.create("marco@yolo.com"));
            Entity luca = person.create().has(name.create("luca")).has(email.create("luca@yolo.com"));
            hasCast.create().assign(actor, marco).assign(characterBeingPlayed, luca);
            tx.commit();
        }
        try (GraknTx remoteTx = remoteSession.transaction(GraknTxType.READ);
             GraknTx localTx = localSession.transaction(GraknTxType.READ)
        ) {
            GetQuery query = remoteTx.graql().match(var("x").isa("has-cast")).get();
            Relationship remoteConcept = query.stream().findAny().get().get("x").asRelationship();
            Relationship localConcept = localTx.getConcept(remoteConcept.id()).asRelationship();

            assertEqualConcepts(localConcept, remoteConcept, Relationship::rolePlayers);

            ImmutableMultimap.Builder<ConceptId, ConceptId> localRolePlayers = ImmutableMultimap.builder();
            localConcept.rolePlayersMap().forEach((role, players) -> {
                for (Thing player : players) {
                    localRolePlayers.put(role.id(), player.id());
                }
            });

            ImmutableMultimap.Builder<ConceptId, ConceptId> remoteRolePlayers = ImmutableMultimap.builder();
            remoteConcept.rolePlayersMap().forEach((role, players) -> {
                for (Thing player : players) {
                    remoteRolePlayers.put(role.id(), player.id());
                }
            });

            assertEquals(localRolePlayers.build(), remoteRolePlayers.build());
        }
    }


    @Test
    public void testGettingASchemaConcept_TheInformationOnTheSchemaConceptIsCorrect() {
        try (GraknTx tx = localSession.transaction(GraknTxType.WRITE)) {
            EntityType human = tx.putEntityType("human");
            EntityType man = tx.putEntityType("man").sup(human);
            tx.putEntityType("child").sup(man);
            tx.commit();
        }
        try (GraknTx remoteTx = remoteSession.transaction(GraknTxType.READ);
             GraknTx localTx = localSession.transaction(GraknTxType.READ)
        ) {
            GetQuery query = remoteTx.graql().match(var("x").label("man")).get();
            SchemaConcept remoteConcept = query.stream().findAny().get().get("x").asSchemaConcept();
            SchemaConcept localConcept = localTx.getConcept(remoteConcept.id()).asSchemaConcept();

            assertEquals(localConcept.isImplicit(), remoteConcept.isImplicit());
            assertEquals(localConcept.label(), remoteConcept.label());
            assertEquals(localConcept.sup().id(), remoteConcept.sup().id());
            assertEqualConcepts(localConcept, remoteConcept, SchemaConcept::sups);
            assertEqualConcepts(localConcept, remoteConcept, SchemaConcept::subs);
        }
    }

    @Test
    public void testGettingAThing_TheInformationOnTheThingIsCorrect() {
        try (GraknTx tx = localSession.transaction(GraknTxType.WRITE)) {
            EntityType person = tx.putEntityType("person");
            AttributeType name = tx.putAttributeType("name", DataType.STRING);
            AttributeType email = tx.putAttributeType("email", DataType.STRING);
            Role actor = tx.putRole("actor");
            Role characterBeingPlayed = tx.putRole("character-being-played");
            RelationshipType hasCast = tx.putRelationshipType("has-cast").relates(actor).relates(characterBeingPlayed);
            person.key(email).has(name);
            person.plays(actor).plays(characterBeingPlayed);

            Entity marco = person.create().has(name.create("marco")).has(email.create("marco@yolo.com"));
            Entity luca = person.create().has(name.create("luca")).has(email.create("luca@yolo.com"));
            hasCast.create().assign(actor, marco).assign(characterBeingPlayed, luca);
            tx.commit();
        }
        try (GraknTx remoteTx = remoteSession.transaction(GraknTxType.READ);
             GraknTx localTx = localSession.transaction(GraknTxType.READ)
        ) {
            GetQuery query = remoteTx.graql().match(var("x").isa("person")).get();
            Thing remoteConcept = query.stream().findAny().get().get("x").asThing();
            Thing localConcept = localTx.getConcept(remoteConcept.id()).asThing();

            assertEquals(localConcept.isInferred(), remoteConcept.isInferred());
            assertEquals(localConcept.type().id(), remoteConcept.type().id());
            assertEqualConcepts(localConcept, remoteConcept, Thing::attributes);
            assertEqualConcepts(localConcept, remoteConcept, Thing::keys);
//            assertEqualConcepts(localConcept, remoteConcept, Thing::plays); // TODO: re-enable when #19630 is fixed
            assertEqualConcepts(localConcept, remoteConcept, Thing::relationships);
        }
    }

    @Test
    public void testGettingAType_TheInformationOnTheTypeIsCorrect() {
        try (GraknTx tx = localSession.transaction(GraknTxType.WRITE)) {
            Role productionWithCast = tx.putRole("production-with-cast");
            Role actor = tx.putRole("actor");
            Role characterBeingPlayed = tx.putRole("character-being-played");
            tx.putRelationshipType("has-cast").relates(productionWithCast).relates(actor).relates(characterBeingPlayed);
            EntityType person = tx.putEntityType("person").plays(actor).plays(characterBeingPlayed);

            person.has(tx.putAttributeType("gender", DataType.STRING));
            person.has(tx.putAttributeType("name", DataType.STRING));

            person.create();
            person.create();
            tx.commit();
        }
        try (GraknTx remoteTx = remoteSession.transaction(GraknTxType.READ);
             GraknTx localTx = localSession.transaction(GraknTxType.READ)
        ) {
            GetQuery query = remoteTx.graql().match(var("x").label("person")).get();
            Type remoteConcept = query.stream().findAny().get().get("x").asType();
            Type localConcept = localTx.getConcept(remoteConcept.id()).asType();

            assertEquals(localConcept.isAbstract(), remoteConcept.isAbstract());
            assertEqualConcepts(localConcept, remoteConcept, Type::playing);
            assertEqualConcepts(localConcept, remoteConcept, Type::instances);
            assertEqualConcepts(localConcept, remoteConcept, Type::attributes);
            assertEqualConcepts(localConcept, remoteConcept, Type::keys);
        }
    }

    @Test
    public void testGettingARole_TheInformationOnTheRoleIsCorrect() {
        try (GraknTx localTx = localSession.transaction(GraknTxType.WRITE)) {
            Role productionWithCast = localTx.putRole("production-with-cast");
            Role actor = localTx.putRole("actor");
            Role characterBeingPlayed = localTx.putRole("character-being-played");
            localTx.putRelationshipType("has-cast")
                    .relates(productionWithCast).relates(actor).relates(characterBeingPlayed);
            localTx.commit();
        }
        try (GraknTx remoteTx = remoteSession.transaction(GraknTxType.READ);
             GraknTx localTx = localSession.transaction(GraknTxType.READ)
        ) {
            GetQuery query = remoteTx.graql().match(var("x").label("actor")).get();
            Role remoteConcept = query.stream().findAny().get().get("x").asRole();
            Role localConcept = localTx.getConcept(remoteConcept.id()).asRole();

            assertEqualConcepts(localConcept, remoteConcept, Role::players);
            assertEqualConcepts(localConcept, remoteConcept, Role::relationships);
        }
    }

    @Test
    public void testGettingARule_TheInformationOnTheRuleIsCorrect() {
        try (GraknTx tx = localSession.transaction(GraknTxType.WRITE)) {
            tx.putAttributeType("name", DataType.STRING);
            Pattern when = tx.graql().parser().parsePattern("$x has name 'expectation-when'");
            Pattern then = tx.graql().parser().parsePattern("$x has name 'expectation-then'");

            tx.putRule("expectation-rule", when, then);

            when = tx.graql().parser().parsePattern("$x has name 'materialize-when'");
            then = tx.graql().parser().parsePattern("$x has name 'materialize-then'");
            tx.putRule("materialize-rule", when, then);
            tx.commit();
        }

        try (GraknTx remoteTx = remoteSession.transaction(GraknTxType.READ);
             GraknTx localTx = localSession.transaction(GraknTxType.READ)
        ) {
            GetQuery query = remoteTx.graql().match(var("x").label("expectation-rule")).get();
            ai.grakn.concept.Rule remoteConcept = query.stream().findAny().get().get("x").asRule();
            ai.grakn.concept.Rule localConcept = localTx.getConcept(remoteConcept.id()).asRule();

            assertEquals(localConcept.when(), remoteConcept.when());
            assertEquals(localConcept.then(), remoteConcept.then());
        }
    }

    @Test
    public void testGettingAnEntityType_TheInformationOnTheEntityTypeIsCorrect() {
        try (GraknTx localTx = localSession.transaction(GraknTxType.WRITE)) {
            localTx.putEntityType("person");
            localTx.commit();
        }
        try (GraknTx remoteTx = remoteSession.transaction(GraknTxType.READ);
             GraknTx localTx = localSession.transaction(GraknTxType.READ)
        ) {
            GetQuery query = remoteTx.graql().match(var("x").label("person")).get();
            EntityType remoteConcept = query.stream().findAny().get().get("x").asEntityType();
            EntityType localConcept = localTx.getConcept(remoteConcept.id()).asEntityType();

            // There actually aren't any new methods on EntityType, but we should still check we can get them
            assertEquals(localConcept.id(), remoteConcept.id());
        }
    }

    @Test
    public void testGettingARelationshipType_TheInformationOnTheRelationshipTypeIsCorrect() {
        try (GraknTx localTx = localSession.transaction(GraknTxType.WRITE)) {
            Role productionWithCast = localTx.putRole("production-with-cast");
            Role actor = localTx.putRole("actor");
            Role characterBeingPlayed = localTx.putRole("character-being-played");
            localTx.putRelationshipType("has-cast")
                    .relates(productionWithCast).relates(actor).relates(characterBeingPlayed);
            localTx.commit();
        }
        try (GraknTx remoteTx = remoteSession.transaction(GraknTxType.READ);
             GraknTx localTx = localSession.transaction(GraknTxType.READ)
        ) {
            GetQuery query = remoteTx.graql().match(var("x").label("has-cast")).get();
            RelationshipType remoteConcept = query.stream().findAny().get().get("x").asRelationshipType();
            RelationshipType localConcept = localTx.getConcept(remoteConcept.id()).asRelationshipType();

            assertEqualConcepts(localConcept, remoteConcept, RelationshipType::roles);
        }
    }


    @Test
    public void testGettingAnAttributeType_TheInformationOnTheAttributeTypeIsCorrect() {
        try (GraknTx localTx = localSession.transaction(GraknTxType.WRITE)) {
            AttributeType title = localTx.putAttributeType("title", DataType.STRING);
            title.create("The Muppets");
            localTx.commit();
        }
        try (GraknTx remoteTx = remoteSession.transaction(GraknTxType.READ);
             GraknTx localTx = localSession.transaction(GraknTxType.READ)
        ) {
            GetQuery query = remoteTx.graql().match(var("x").label("title")).get();
            AttributeType<String> remoteConcept = query.stream().findAny().get().get("x").asAttributeType();
            AttributeType<String> localConcept = localTx.getConcept(remoteConcept.id()).asAttributeType();

            assertEquals(localConcept.dataType(), remoteConcept.dataType());
            assertEquals(localConcept.regex(), remoteConcept.regex());
            assertEquals(
                    localConcept.attribute("The Muppets").id(),
                    remoteConcept.attribute("The Muppets").id()
            );
        }
    }

    @Test
    public void testGettingAnEntity_TheInformationOnTheEntityIsCorrect() {
        try (GraknTx localTx = localSession.transaction(GraknTxType.WRITE)) {
            EntityType movie = localTx.putEntityType("movie");
            movie.create();
            localTx.commit();
        }
        try (GraknTx remoteTx = remoteSession.transaction(GraknTxType.READ);
             GraknTx localTx = localSession.transaction(GraknTxType.READ)
        ) {
            GetQuery query = remoteTx.graql().match(var("x").isa("movie")).get();
            Entity remoteConcept = query.stream().findAny().get().get("x").asEntity();
            Entity localConcept = localTx.getConcept(remoteConcept.id()).asEntity();

            // There actually aren't any new methods on Entity, but we should still check we can get them
            assertEquals(localConcept.id(), remoteConcept.id());
        }
    }

    @Test
    public void testGettingAnAttribute_TheInformationOnTheAttributeIsCorrect() {
        try (GraknTx localTx = localSession.transaction(GraknTxType.WRITE)) {
            EntityType person = localTx.putEntityType("person");
            AttributeType name = localTx.putAttributeType("name", DataType.STRING);
            person.has(name);
            Attribute alice = name.create("Alice");
            person.create().has(alice);
            localTx.commit();
        }

        try (GraknTx remoteTx = remoteSession.transaction(GraknTxType.READ);
             GraknTx localTx = localSession.transaction(GraknTxType.READ)
        ) {
            GetQuery query = remoteTx.graql().match(var("x").isa("name")).get();
            Attribute<?> remoteConcept = query.stream().findAny().get().get("x").asAttribute();
            Attribute<?> localConcept = localTx.getConcept(remoteConcept.id()).asAttribute();

            assertEquals(localConcept.dataType(), remoteConcept.dataType());
            assertEquals(localConcept.value(), remoteConcept.value());
            assertEquals(localConcept.owner().id(), remoteConcept.owner().id());
            assertEqualConcepts(localConcept, remoteConcept, Attribute::owners);
        }
    }


    @Test
    public void testExecutingComputeQueryies_ResultsAreCorrect() {
        ConceptId idCoco, idMike, idCocoAndMike;
        try (GraknTx tx = localSession.transaction(GraknTxType.WRITE)) {
            Role pet = tx.putRole("pet");
            Role owner = tx.putRole("owner");
            EntityType animal = tx.putEntityType("animal").plays(pet);
            EntityType human = tx.putEntityType("human").plays(owner);
            RelationshipType petOwnership = tx.putRelationshipType("pet-ownership").relates(pet).relates(owner);
            AttributeType<Long> age = tx.putAttributeType("age", DataType.LONG);
            human.has(age);

            Entity coco = animal.create();
            Entity mike = human.create();
            Relationship cocoAndMike = petOwnership.create().assign(pet, coco).assign(owner, mike);
            mike.has(age.create(10L));

            idCoco = coco.id();
            idMike = mike.id();
            idCocoAndMike = cocoAndMike.id();

            tx.commit();
        }

        try (Grakn.Transaction tx = remoteSession.transaction(GraknTxType.READ)) {
            // count
            assertEquals(1, tx.graql().compute(COUNT).in("animal").execute().get(0).number().intValue());

            // statistics
            assertEquals(10, tx.graql().compute(MIN).of("age").in("human").execute().get(0).number().intValue());
            assertEquals(10, tx.graql().compute(MAX).of("age").in("human").execute().get(0).number().intValue());
            assertEquals(10, tx.graql().compute(MEAN).of("age").in("human").execute().get(0).number().intValue());


            List<Value> answer = tx.graql().compute(STD).of("age").in("human").execute();
            assertEquals(0, answer.get(0).number().intValue());


            assertEquals(10, tx.graql().compute(SUM).of("age").in("human").execute().get(0).number().intValue());
            assertEquals(10, tx.graql().compute(MEDIAN).of("age").in("human").execute().get(0).number().intValue());

            // degree
            List<ConceptSetMeasure> centrality = tx.graql().compute(CENTRALITY).using(DEGREE)
                    .of("animal").in("human", "animal", "pet-ownership").execute();
            assertEquals(1, centrality.size());
            assertEquals(idCoco, centrality.get(0).set().iterator().next());
            assertEquals(1, centrality.get(0).measurement().intValue());

            // coreness
            assertTrue(tx.graql().compute(CENTRALITY).using(K_CORE).of("animal").execute().isEmpty());

            // path
            List<ConceptList> paths = tx.graql().compute(PATH).to(idCoco).from(idMike).execute();
            assertEquals(1, paths.size());
            assertEquals(idCoco, paths.get(0).list().get(2));
            assertEquals(idMike, paths.get(0).list().get(0));

            // connected component
            List<ConceptSet> clusterList = tx.graql().compute(CLUSTER).using(CONNECTED_COMPONENT)
                    .in("human", "animal", "pet-ownership").execute();
            assertEquals(1, clusterList.size());
            assertEquals(3, clusterList.get(0).set().size());
            assertEquals(Sets.newHashSet(idCoco, idMike, idCocoAndMike), clusterList.get(0).set());

            // k-core
            assertTrue(tx.graql().compute(CLUSTER).using(K_CORE).in("human", "animal", "pet-ownership").execute().isEmpty());
        }
    }

    @Test
    public void testExecutingAggregateQueries_theResultsAreCorrect() {
        try (Grakn.Transaction tx = remoteSession.transaction(GraknTxType.WRITE)) {
            EntityType person = tx.putEntityType("person");
            AttributeType name = tx.putAttributeType("name", DataType.STRING);
            AttributeType age = tx.putAttributeType("age", DataType.INTEGER);
            AttributeType rating = tx.putAttributeType("rating", DataType.DOUBLE);

            person.has(name).has(age).has(rating);

            person.create().has(name.create("Alice")).has(age.create(20));
            person.create().has(name.create("Bob")).has(age.create(22));

            AggregateQuery<Value> nullQuery =
                    tx.graql().match(var("x").isa("person").has("rating", var("y"))).aggregate(sum("y"));
            assertTrue(nullQuery.execute().isEmpty());

            AggregateQuery<Value> countQuery =
                    tx.graql().match(var("x").isa("person").has("age", var("y"))).aggregate(count("y"));
            assertEquals(2L, countQuery.execute().get(0).number().longValue());

            AggregateQuery<Value> sumAgeQuery =
                    tx.graql().match(var("x").isa("person").has("age", var("y"))).aggregate(sum("y"));
            assertEquals(42, sumAgeQuery.execute().get(0).number().intValue());

            AggregateQuery<Value> minAgeQuery =
                    tx.graql().match(var("x").isa("person").has("age", var("y"))).aggregate(min("y"));
            assertEquals(20, minAgeQuery.execute().get(0).number().intValue());

            AggregateQuery<Value> maxAgeQuery =
                    tx.graql().match(var("x").isa("person").has("age", var("y"))).aggregate(max("y"));
            assertEquals(22, maxAgeQuery.execute().get(0).number().intValue());

            AggregateQuery<Value> meanAgeQuery =
                    tx.graql().match(var("x").isa("person").has("age", var("y"))).aggregate(mean("y"));
            assertEquals(21.0d, meanAgeQuery.execute().get(0).number().doubleValue(), 0.01d);

            AggregateQuery<Value> medianAgeQuery =
                    tx.graql().match(var("x").isa("person").has("age", var("y"))).aggregate(median("y"));
            assertEquals(21.0d, medianAgeQuery.execute().get(0).number().doubleValue(), 0.01d);

            AggregateQuery<Value> stdAgeQuery =
                    tx.graql().match(var("x").isa("person").has("age", var("y"))).aggregate(std("y"));
            int n = 2;
            double mean = (20 + 22) / n;
            double var = (Math.pow(20 - mean, 2) + Math.pow(22 - mean, 2)) / (n - 1);
            double std = Math.sqrt(var);
            assertEquals(std, stdAgeQuery.execute().get(0).number().doubleValue(), 0.0001d);

            List<AnswerGroup<ConceptMap>> groups = tx.graql().match(var("x").isa("person").has("name", var("y")))
                    .aggregate(group("y"))
                    .execute();

            assertEquals(2, groups.size());
            groups.forEach(group -> {
                group.answers().forEach(answer -> {
                    assertTrue(answer.get("x").asEntity().attributes(name).collect(toSet()).contains(group.owner()));
                });
            });

            List<AnswerGroup<Value>> counts = tx.graql().match(var("x").isa("person").has("name", var("y")))
                    .aggregate(group("y", count()))
                    .execute();

            assertEquals(2, counts.size());
            counts.forEach(group -> {
                group.answers().forEach(answer -> {
                    assertEquals(1, answer.number().intValue());
                });
            });
        }
    }

    @Test
    public void testDeletingAConcept_TheConceptIsDeleted() {
        Label label = Label.of("hello");

        try (GraknTx tx = localSession.transaction(GraknTxType.WRITE)) {
            tx.putEntityType(label);
            tx.commit();
        }

        try (Grakn.Transaction tx = remoteSession.transaction(GraknTxType.WRITE)) {
            SchemaConcept schemaConcept = tx.getSchemaConcept(label);
            assertFalse(schemaConcept.isDeleted());
            schemaConcept.delete();
            assertTrue(schemaConcept.isDeleted());
            tx.commit();
        }

        try (GraknTx tx = localSession.transaction(GraknTxType.WRITE)) {
            assertNull(tx.getSchemaConcept(label));
        }
    }

    @Test
    public void testDefiningASchema_TheSchemaIsDefined() {
        try (Grakn.Transaction tx = remoteSession.transaction(GraknTxType.WRITE)) {
            EntityType animal = tx.putEntityType("animal");
            EntityType dog = tx.putEntityType("dog").sup(animal);
            EntityType cat = tx.putEntityType("cat");
            cat.sup(animal);

            cat.label(Label.of("feline"));
            dog.isAbstract(true).isAbstract(false);
            cat.isAbstract(true);

            RelationshipType chases = tx.putRelationshipType("chases");
            Role chased = tx.putRole("chased");
            Role chaser = tx.putRole("chaser");
            chases.relates(chased).relates(chaser);

            Role pointlessRole = tx.putRole("pointless-role");
            tx.putRelationshipType("pointless").relates(pointlessRole);

            chases.relates(pointlessRole).unrelate(pointlessRole);

            dog.plays(chaser);
            cat.plays(chased);

            AttributeType<String> name = tx.putAttributeType("name", DataType.STRING);
            AttributeType<String> id = tx.putAttributeType("id", DataType.STRING).regex("(good|bad)-dog");
            AttributeType<Long> age = tx.putAttributeType("age", DataType.LONG);

            animal.has(name);
            animal.key(id);

            dog.has(age).unhas(age);
            cat.key(age).unkey(age);
            cat.plays(chaser).unplay(chaser);

            Entity dunstan = dog.create();
            Attribute<String> dunstanId = id.create("good-dog");
            assertNotNull(dunstan.relhas(dunstanId));

            Attribute<String> dunstanName = name.create("Dunstan");
            dunstan.has(dunstanName).unhas(dunstanName);

            chases.create().assign(chaser, dunstan);

            Set<Attribute> set = dunstan.keys(name).collect(toSet());
            assertEquals(0, set.size());

            tx.commit();
        }

        try (GraknTx tx = localSession.transaction(GraknTxType.READ)) {
            EntityType animal = tx.getEntityType("animal");
            EntityType dog = tx.getEntityType("dog");
            EntityType cat = tx.getEntityType("feline");
            RelationshipType chases = tx.getRelationshipType("chases");
            Role chased = tx.getRole("chased");
            Role chaser = tx.getRole("chaser");
            AttributeType<String> name = tx.getAttributeType("name");
            AttributeType<String> id = tx.getAttributeType("id");
            Entity dunstan = Iterators.getOnlyElement(dog.instances().iterator());
            Relationship aChase = Iterators.getOnlyElement(chases.instances().iterator());

            assertEquals(animal, dog.sup());
            assertEquals(animal, cat.sup());

            assertEquals(ImmutableSet.of(chased, chaser), chases.roles().collect(toSet()));
            assertEquals(ImmutableSet.of(chaser), dog.playing().filter(role -> !role.isImplicit()).collect(toSet()));
            assertEquals(ImmutableSet.of(chased), cat.playing().filter(role -> !role.isImplicit()).collect(toSet()));

            assertEquals(ImmutableSet.of(name, id), animal.attributes().collect(toSet()));
            assertEquals(ImmutableSet.of(id), animal.keys().collect(toSet()));

            assertEquals(ImmutableSet.of(name, id), dog.attributes().collect(toSet()));
            assertEquals(ImmutableSet.of(id), dog.keys().collect(toSet()));

            assertEquals(ImmutableSet.of(name, id), cat.attributes().collect(toSet()));
            assertEquals(ImmutableSet.of(id), cat.keys().collect(toSet()));

            assertEquals("good-dog", Iterables.getOnlyElement(dunstan.keys(id).collect(toSet())).value());

            ImmutableMap<Role, ImmutableSet<?>> expectedRolePlayers =
                    ImmutableMap.of(chaser, ImmutableSet.of(dunstan), chased, ImmutableSet.of());

            assertEquals(expectedRolePlayers, aChase.rolePlayersMap());

            assertEquals("(good|bad)-dog", id.regex());

            assertFalse(dog.isAbstract());
            assertTrue(cat.isAbstract());
        }
    }

    @Test
    public void testDeletingAKeyspace_TheKeyspaceIsDeleted() {
        Grakn client = new Grakn(server.grpcUri());
        GraknSession localSession = server.sessionWithNewKeyspace();
        Keyspace ks = localSession.keyspace();
        Grakn.Session remoteSession = client.session(ks);

        try (GraknTx tx = localSession.transaction(GraknTxType.WRITE)) {
            tx.putEntityType("easter");
            tx.commit();
        }

        try (Grakn.Transaction tx = remoteSession.transaction(GraknTxType.WRITE)) {
            assertNotNull(tx.getEntityType("easter"));

            client.keyspaces().delete(tx.keyspace());

            //TODO fix in the following PR
//            assertTrue(tx.isClosed());
        }

        try (GraknTx tx = localSession.transaction(GraknTxType.READ)) {
            assertNull(tx.getEntityType("easter"));
        }
    }

    private <T extends Concept> void assertEqualConcepts(
            T concept1, T concept2, Function<T, Stream<? extends Concept>> function
    ) {
        assertEquals(
                function.apply(concept1).map(Concept::id).collect(toSet()),
                function.apply(concept2).map(Concept::id).collect(toSet())
        );
    }

    @Test
    public void testExecutingAnInvalidQuery_Throw() throws Throwable {
        try (Grakn.Transaction tx = remoteSession.transaction(GraknTxType.READ)) {
            GetQuery query = tx.graql().match(var("x").isa("not-a-thing")).get();

            exception.expect(RuntimeException.class);

            query.execute();
        }
    }

    @Test
    public void testPerformingAMatchGetQuery_TheResultsAreCorrect() {
        try (Grakn.Transaction tx = remoteSession.transaction(GraknTxType.WRITE)) {
            //Graql.match(var("x").isa("company")).get(var("x"), var("y"));

            EntityType company = tx.putEntityType("company-123");
            company.create();
            company.create();

            EntityType person = tx.putEntityType("person-123");
            person.create();
            person.create();
            person.create();

            QueryBuilder qb = tx.graql();
            Var x = var("x");
            Var y = var("y");

            Collection<ConceptMap> result = qb.match(x.isa("company-123"), y.isa("person-123")).get(x, y).execute();
            assertEquals(6, result.size());

            result = qb.match(x.isa("company-123")).get(x).execute();
            assertEquals(2, result.size());
        }
    }

    @Test
    public void testCreatingBasicMultipleTransaction_ThreadsDoNotConflict() {
        Grakn.Transaction tx1 = remoteSession.transaction(GraknTxType.WRITE);
        Grakn.Transaction tx2 = remoteSession.transaction(GraknTxType.WRITE);

        EntityType company = tx1.putEntityType("company");
        EntityType person = tx2.putEntityType("person");

        AttributeType<String> name1 = tx1.putAttributeType(Label.of("name"), DataType.STRING);
        AttributeType<String> name2 = tx2.putAttributeType(Label.of("name"), DataType.STRING);

        company.has(name1);
        person.has(name2);

        Entity google = company.create();
        Entity alice = person.create();

        google.has(name1.create("Google"));
        alice.has(name2.create("Alice"));

        assertTrue(company.attributes().anyMatch(a -> a.equals(name1)));
        assertTrue(person.attributes().anyMatch(a -> a.equals(name2)));

        assertTrue(google.attributes(name1).allMatch(n -> n.value().equals("Google")));
        assertTrue(alice.attributes(name2).allMatch(n -> n.value().equals("Alice")));

        tx1.close();

        Entity bob = person.create();
        bob.has(name2.create("Bob"));

        assertTrue(bob.attributes(name2).allMatch(n -> n.value().equals("Bob")));

        tx2.close();
    }

    @Test
    public void setAttributeValueWithDatatypeDate() {
        try (Grakn.Transaction tx = remoteSession.transaction(GraknTxType.WRITE)) {
            AttributeType<LocalDateTime> birthDateType = tx.putAttributeType(Label.of("birth-date"), DataType.DATE);
            LocalDateTime date = LocalDateTime.now();
            Attribute<LocalDateTime> dateAttribute = birthDateType.create(date);
            assertEquals(date, dateAttribute.value());
        }
    }
}