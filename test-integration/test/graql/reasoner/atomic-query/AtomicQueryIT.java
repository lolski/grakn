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
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ai.grakn.graql.internal.reasoner;

import ai.grakn.GraknSession;
import ai.grakn.GraknTx;
import ai.grakn.GraknTxType;
import ai.grakn.concept.Attribute;
import ai.grakn.concept.Concept;
import ai.grakn.exception.GraqlQueryException;
import ai.grakn.factory.EmbeddedGraknSession;
import ai.grakn.graql.GetQuery;
import ai.grakn.graql.Graql;
import ai.grakn.graql.Query;
import ai.grakn.graql.QueryBuilder;
import ai.grakn.graql.admin.Conjunction;
import ai.grakn.graql.admin.MultiUnifier;
import ai.grakn.graql.admin.PatternAdmin;
import ai.grakn.graql.admin.VarPatternAdmin;
import ai.grakn.graql.answer.ConceptMap;
import ai.grakn.graql.internal.pattern.Patterns;
import ai.grakn.graql.internal.query.answer.ConceptMapImpl;
import ai.grakn.graql.internal.reasoner.atom.Atom;
import ai.grakn.graql.internal.reasoner.graph.GeoGraph;
import ai.grakn.graql.internal.reasoner.query.ReasonerAtomicQuery;
import ai.grakn.graql.internal.reasoner.query.ReasonerQueries;
import ai.grakn.graql.internal.reasoner.unifier.UnifierType;
import ai.grakn.kb.internal.EmbeddedGraknTx;
import ai.grakn.test.rule.ConcurrentGraknServer;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static ai.grakn.graql.Graql.var;
import static ai.grakn.util.GraqlTestUtil.assertExists;
import static ai.grakn.util.GraqlTestUtil.assertNotExists;
import static java.util.stream.Collectors.toSet;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("CheckReturnValue")
public class AtomicQueryIT {

    @ClassRule
    public static final ConcurrentGraknServer server = new ConcurrentGraknServer();

    private static EmbeddedGraknSession materialisationTestSession;
    private static EmbeddedGraknSession geoGraphSession;

    private static void loadFromFile(String fileName, GraknSession session){
        try {
            InputStream inputStream = AtomicQueryIT.class.getClassLoader().getResourceAsStream("test-integration/test/graql/reasoner/resources/"+fileName);
            String s = new BufferedReader(new InputStreamReader(inputStream)).lines().collect(Collectors.joining("\n"));
            GraknTx tx = session.transaction(GraknTxType.WRITE);
            tx.graql().parser().parseList(s).forEach(Query::execute);
            tx.commit();
        } catch (Exception e){
            System.err.println(e);
            throw new RuntimeException(e);
        }
    }

    @BeforeClass
    public static void loadContext(){
        materialisationTestSession = server.sessionWithNewKeyspace();
        loadFromFile("materialisationTest.gql", materialisationTestSession);
        geoGraphSession = server.sessionWithNewKeyspace();
        GeoGraph geoGraph = new GeoGraph(geoGraphSession);
        geoGraph.load();
    }

    @AfterClass
    public static void closeSession() throws Exception {
        materialisationTestSession.close();
        geoGraphSession.close();
        server.cleanup();
    }

    @Test (expected = IllegalArgumentException.class)
    public void testWhenConstructingNonAtomicQuery_ExceptionIsThrown() {
        try(EmbeddedGraknTx<?> tx = geoGraphSession.transaction(GraknTxType.WRITE)) {
            String patternString = "{$x isa university;$y isa country;($x, $y) isa is-located-in;($y, $z) isa is-located-in;}";
            ReasonerAtomicQuery atomicQuery = ReasonerQueries.atomic(conjunction(patternString), tx);
        }
    }

    @Test (expected = GraqlQueryException.class)
    public void testWhenCreatingQueryWithNonexistentType_ExceptionIsThrown(){
        try(EmbeddedGraknTx<?> tx = geoGraphSession.transaction(GraknTxType.WRITE)) {
            String patternString = "{$x isa someType;}";
            ReasonerAtomicQuery query = ReasonerQueries.atomic(conjunction(patternString), tx);
        }
    }

    @Test
    public void testWhenMaterialising_MaterialisedInformationIsPresentInGraph(){
        EmbeddedGraknTx<?> tx = geoGraphSession.transaction(GraknTxType.WRITE);
        QueryBuilder qb = tx.graql().infer(false);
        String explicitQuery = "match (geo-entity: $x, entity-location: $y) isa is-located-in;$x has name 'Warsaw';$y has name 'Poland'; get;";
        assertTrue(!qb.<GetQuery>parse(explicitQuery).iterator().hasNext());

        String patternString = "{(geo-entity: $x, entity-location: $y) isa is-located-in;}";
        Conjunction<VarPatternAdmin> pattern = conjunction(patternString);
        List<ConceptMap> answers = new ArrayList<>();

        answers.add(new ConceptMapImpl(
                ImmutableMap.of(
                        var("x"), getConceptByResourceValue(tx, "Warsaw"),
                        var("y"), getConceptByResourceValue(tx, "Poland")))
        );
        ReasonerAtomicQuery atomicQuery = ReasonerQueries.atomic(pattern, tx);

        assertNotExists(qb.parse(explicitQuery));
        answers.forEach(atomicQuery::materialise);
        assertExists(qb.parse(explicitQuery));
        tx.close();
    }

    @Test
    public void testWhenMaterialisingEntity_MaterialisedInformationIsCorrectlyFlaggedAsInferred(){
        EmbeddedGraknTx<?> tx = materialisationTestSession.transaction(GraknTxType.WRITE);
        ReasonerAtomicQuery entityQuery = ReasonerQueries.atomic(conjunction("$x isa newEntity"), tx);
        assertEquals(entityQuery.materialise(new ConceptMapImpl()).findFirst().orElse(null).get("x").asEntity().isInferred(), true);
        tx.close();
    }

    @Test
    public void testWhenMaterialisingResources_MaterialisedInformationIsCorrectlyFlaggedAsInferred(){
        EmbeddedGraknTx<?> tx = materialisationTestSession.transaction(GraknTxType.WRITE);
        QueryBuilder qb = tx.graql().infer(false);
        Concept firstEntity = Iterables.getOnlyElement(qb.<GetQuery>parse("match $x isa entity1; get;").execute()).get("x");
        Concept secondEntity = Iterables.getOnlyElement(qb.<GetQuery>parse("match $x isa entity2; get;").execute()).get("x");
        Concept resource = Iterables.getOnlyElement(qb.<GetQuery>parse("match $x isa resource; get;").execute()).get("x");

        ReasonerAtomicQuery resourceQuery = ReasonerQueries.atomic(conjunction("{$x has resource $r;$r == 'inferred';$x id " + firstEntity.id().getValue() + ";}"), tx);
        String reuseResourcePatternString =
                "{" +
                        "$x has resource $r;" +
                        "$x id " + secondEntity.id().getValue() + ";" +
                        "$r id " + resource.id().getValue() + ";" +
                        "}";

        ReasonerAtomicQuery reuseResourceQuery = ReasonerQueries.atomic(conjunction(reuseResourcePatternString), tx);

        assertEquals(resourceQuery.materialise(new ConceptMapImpl()).findFirst().orElse(null).get("r").asAttribute().isInferred(), true);

        reuseResourceQuery.materialise(new ConceptMapImpl()).collect(Collectors.toList());
        assertEquals(Iterables.getOnlyElement(
                qb.<GetQuery>parse("match" +
                        "$x has resource $r via $rel;" +
                        "$x id " + secondEntity.id().getValue() + ";" +
                        "$r id " + resource.id().getValue() + ";" +
                        "get;").execute()).get("rel").asRelationship().isInferred(), true);
        assertEquals(Iterables.getOnlyElement(
                qb.<GetQuery>parse("match" +
                        "$x has resource $r via $rel;" +
                        "$x id " + firstEntity.id().getValue() + ";" +
                        "$r id " + resource.id().getValue() + ";" +
                        "get;").execute()).get("rel").asRelationship().isInferred(), false);
        tx.close();
    }

    @Test
    public void testWhenMaterialisingRelations_MaterialisedInformationIsCorrectlyFlaggedAsInferred(){
        EmbeddedGraknTx<?> tx = materialisationTestSession.transaction(GraknTxType.WRITE);
        QueryBuilder qb = tx.graql().infer(false);
        Concept firstEntity = Iterables.getOnlyElement(qb.<GetQuery>parse("match $x isa entity1; get;").execute()).get("x");
        Concept secondEntity = Iterables.getOnlyElement(qb.<GetQuery>parse("match $x isa entity2; get;").execute()).get("x");

        ReasonerAtomicQuery relationQuery = ReasonerQueries.atomic(conjunction(
                "{" +
                        "$r (role1: $x, role2: $y);" +
                        "$x id " + firstEntity.id().getValue() + ";" +
                        "$y id " + secondEntity.id().getValue() + ";" +
                        "}"
                ),
                tx
        );

        assertEquals(relationQuery.materialise(new ConceptMapImpl()).findFirst().orElse(null).get("r").asRelationship().isInferred(), true);
        tx.close();
    }

    @Test
    public void testWhenCopying_TheCopyIsAlphaEquivalent(){
        EmbeddedGraknTx<?> tx = geoGraphSession.transaction(GraknTxType.WRITE);
        String patternString = "{($x, $y) isa is-located-in;}";
        Conjunction<VarPatternAdmin> pattern = conjunction(patternString);
        ReasonerAtomicQuery atomicQuery = ReasonerQueries.atomic(pattern, tx);
        ReasonerAtomicQuery copy = ReasonerQueries.atomic(atomicQuery);
        assertEquals(atomicQuery, copy);
        assertEquals(atomicQuery.hashCode(), copy.hashCode());
        tx.close();
    }

    @Test
    public void testWhenRoleTypesAreAmbiguous_answersArePermutedCorrectly(){
        EmbeddedGraknTx<?> tx = geoGraphSession.transaction(GraknTxType.WRITE);
        String childString = "match (geo-entity: $x, entity-location: $y) isa is-located-in; get;";
        String parentString = "match ($x, $y) isa is-located-in; get;";

        QueryBuilder qb = tx.graql().infer(false);
        GetQuery childQuery = qb.parse(childString);
        GetQuery parentQuery = qb.parse(parentString);
        Set<ConceptMap> answers = childQuery.stream().collect(toSet());
        Set<ConceptMap> fullAnswers = parentQuery.stream().collect(toSet());
        Atom childAtom = ReasonerQueries.atomic(conjunction(childQuery.match().admin().getPattern()), tx).getAtom();
        Atom parentAtom = ReasonerQueries.atomic(conjunction(parentQuery.match().admin().getPattern()), tx).getAtom();

        MultiUnifier multiUnifier = childAtom.getMultiUnifier(childAtom, UnifierType.RULE);
        Set<ConceptMap> permutedAnswers = answers.stream()
                .flatMap(a -> multiUnifier.stream().map(a::unify))
                .collect(Collectors.toSet());

        MultiUnifier multiUnifier2 = childAtom.getMultiUnifier(parentAtom, UnifierType.RULE);
        Set<ConceptMap> permutedAnswers2 = answers.stream()
                .flatMap(a -> multiUnifier2.stream().map(a::unify))
                .collect(Collectors.toSet());

        assertEquals(fullAnswers, permutedAnswers2);
        assertEquals(answers, permutedAnswers);
        tx.close();
    }

    private Conjunction<VarPatternAdmin> conjunction(String patternString){
        Set<VarPatternAdmin> vars = Graql.parser().parsePattern(patternString).admin()
                .getDisjunctiveNormalForm().getPatterns()
                .stream().flatMap(p -> p.getPatterns().stream()).collect(toSet());
        return Patterns.conjunction(vars);
    }

    private Conjunction<VarPatternAdmin> conjunction(Conjunction<PatternAdmin> pattern){
        Set<VarPatternAdmin> vars = pattern
                .getDisjunctiveNormalForm().getPatterns()
                .stream().flatMap(p -> p.getPatterns().stream()).collect(toSet());
        return Patterns.conjunction(vars);
    }


    private Concept getConceptByResourceValue(EmbeddedGraknTx<?> tx, String id){
        Set<Concept> instances = tx.getAttributesByValue(id)
                .stream().flatMap(Attribute::owners).collect(Collectors.toSet());
        if (instances.size() != 1)
            throw new IllegalStateException("Something wrong, multiple instances with given res value");
        return instances.iterator().next();
    }

}
