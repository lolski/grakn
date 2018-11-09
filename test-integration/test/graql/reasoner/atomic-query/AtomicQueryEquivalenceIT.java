package ai.grakn.graql.internal.reasoner;


import ai.grakn.GraknSession;
import ai.grakn.GraknTx;
import ai.grakn.GraknTxType;
import ai.grakn.factory.EmbeddedGraknSession;
import ai.grakn.graql.Graql;
import ai.grakn.graql.Query;
import ai.grakn.graql.admin.Atomic;
import ai.grakn.graql.admin.Conjunction;
import ai.grakn.graql.admin.VarPatternAdmin;
import ai.grakn.graql.internal.pattern.Patterns;
import ai.grakn.graql.internal.reasoner.atom.AtomicEquivalence;
import ai.grakn.graql.internal.reasoner.query.ReasonerAtomicQuery;
import ai.grakn.graql.internal.reasoner.query.ReasonerQueries;
import ai.grakn.graql.internal.reasoner.query.ReasonerQueryEquivalence;
import ai.grakn.kb.internal.EmbeddedGraknTx;
import ai.grakn.test.rule.ConcurrentGraknServer;
import ai.grakn.util.Schema;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toSet;
import static org.junit.Assert.assertEquals;

@SuppressWarnings("CheckReturnValue")
public class AtomicQueryEquivalenceIT {

    @ClassRule
    public static final ConcurrentGraknServer server = new ConcurrentGraknServer();

    private static EmbeddedGraknSession genericSchemaSession;

    private static void loadFromFile(String fileName, GraknSession session){
        try {
            InputStream inputStream = AtomicQueryEquivalenceIT.class.getClassLoader().getResourceAsStream("test-integration/test/graql/reasoner/resources/"+fileName);
            String s = new BufferedReader(new InputStreamReader(inputStream)).lines().collect(Collectors.joining("\n"));
            GraknTx tx = session.transaction(GraknTxType.WRITE);
            tx.graql().parser().parseList(s).forEach(Query::execute);
            tx.commit();
        } catch (Exception e){
            System.err.println(e);
            throw new RuntimeException(e);
        }
    }

    private EmbeddedGraknTx tx;

    @BeforeClass
    public static void loadContext(){
        genericSchemaSession = server.sessionWithNewKeyspace();
        loadFromFile("genericSchema.gql", genericSchemaSession);
    }

    @AfterClass
    public static void closeSession() throws Exception {
        genericSchemaSession.close();
        server.cleanup();
    }

    @Before
    public void setUp(){
        tx = genericSchemaSession.transaction(GraknTxType.WRITE);
    }

    @After
    public void tearDown(){
        tx.close();
    }

    @Test
    public void testEquivalence_DifferentIsaVariants(){
        testEquivalence_DifferentTypeVariants(tx, "isa", "baseRoleEntity", "subRoleEntity");
    }

    @Test
    public void testEquivalence_DifferentSubVariants(){
        testEquivalence_DifferentTypeVariants(tx, "sub", "baseRoleEntity", "baseRole1");
    }

    @Test
    public void testEquivalence_DifferentPlaysVariants(){
        testEquivalence_DifferentTypeVariants(tx, "plays", "baseRole1", "baseRole2");
    }

    @Test
    public void testEquivalence_DifferentRelatesVariants(){
        testEquivalence_DifferentTypeVariants(tx, "relates", "baseRole1", "baseRole2");
    }

    @Test
    public void testEquivalence_DifferentHasVariants(){
        String query = "{$x has resource;}";
        String query2 = "{$y has resource;}";
        String query3 = "{$x has " + Schema.MetaSchema.ATTRIBUTE.getLabel().getValue() + ";}";

        ArrayList<String> queries = Lists.newArrayList(query, query2, query3);

        equivalence(query, queries, Lists.newArrayList(query2), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query, queries, Lists.newArrayList(query2), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query2, queries, Lists.newArrayList(query), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query2, queries, Lists.newArrayList(query), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query3, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query3, queries, new ArrayList<>(), ReasonerQueryEquivalence.StructuralEquivalence, tx);
    }

    @Test
    public void testEquivalence_TypesWithSameLabel(){
        String isaQuery = "{$x isa baseRoleEntity;}";
        String subQuery = "{$x sub baseRoleEntity;}";

        String playsQuery = "{$x plays baseRole1;}";
        String relatesQuery = "{$x relates baseRole1;}";
        String hasQuery = "{$x has resource;}";
        String subQuery2 = "{$x sub baseRole1;}";

        ArrayList<String> queries = Lists.newArrayList(isaQuery, subQuery, playsQuery, relatesQuery, hasQuery, subQuery2);

        equivalence(isaQuery, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(isaQuery, queries, new ArrayList<>(), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(subQuery, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(subQuery, queries, new ArrayList<>(), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(playsQuery, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(playsQuery, queries, new ArrayList<>(), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(relatesQuery, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(relatesQuery, queries, new ArrayList<>(), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(hasQuery, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(hasQuery, queries, new ArrayList<>(), ReasonerQueryEquivalence.StructuralEquivalence, tx);
    }

    private void testEquivalence_DifferentTypeVariants(EmbeddedGraknTx<?> tx, String keyword, String label, String label2){
        String query = "{$x " + keyword + " " + label + ";}";
        String query2 = "{$y " + keyword + " $type;$type label " + label +";}";
        String query3 = "{$z " + keyword + " $t;$t label " + label +";}";
        String query4 = "{$x " + keyword + " $y;}";
        String query5 = "{$x " + keyword + " " + label2 + ";}";

        ArrayList<String> queries = Lists.newArrayList(query, query2, query3, query4, query5);

        equivalence(query, queries, Lists.newArrayList(query2, query3), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query, queries, Lists.newArrayList(query2, query3), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query2, queries, Lists.newArrayList(query, query3), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query2, queries, Lists.newArrayList(query, query3), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query3, queries, Lists.newArrayList(query, query2), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query3, queries, Lists.newArrayList(query, query2), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query4, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query4, queries, new ArrayList<>(), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query5, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query5, queries, new ArrayList<>(), ReasonerQueryEquivalence.StructuralEquivalence, tx);
    }

    @Test
    public void testEquivalence_DifferentRelationInequivalentVariants(){

        HashSet<String> queries = Sets.newHashSet(
                "{$x isa binary;}",
                "{($y) isa binary;}",

                "{($x, $y);}",
                "{($x, $y) isa binary;}",
                "{(baseRole1: $x, baseRole2: $y) isa binary;}",
                "{(role: $y, baseRole2: $z) isa binary;}",
                "{(role: $y, baseRole2: $z) isa $type;}",
                "{(role: $y, baseRole2: $z) isa $type; $type label binary;}",
                "{(role: $x, role: $x, baseRole2: $z) isa binary;}",

                "{$x ($y, $z) isa binary;}",
                "{$x (baseRole1: $y, baseRole2: $z) isa binary;}"
        );

        queries.forEach(qA -> {
            queries.stream()
                    .filter(qB -> !qA.equals(qB))
                    .forEach(qB -> {
                        equivalence(qA, qB, false, ReasonerQueryEquivalence.AlphaEquivalence, tx);
                        equivalence(qA, qB, false, ReasonerQueryEquivalence.StructuralEquivalence, tx);
                    });
        });
    }

    @Test
    public void testEquivalence_RelationWithRepeatingVariables(){
        String query = "{(baseRole1: $x, baseRole2: $y);}";
        String query2 = "{(baseRole1: $x, baseRole2: $x);}";

        equivalence(query, query2, false, ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query, query2, false, ReasonerQueryEquivalence.StructuralEquivalence, tx);
    }

    @Test
    public void testEquivalence_RelationsWithTypedRolePlayers(){
        String query = "{(role: $x, role: $y);$x isa baseRoleEntity;}";
        String query2 = "{(role: $x, role: $y);$y isa baseRoleEntity;}";
        String query3 = "{(role: $x, role: $y);$x isa subRoleEntity;}";
        String query4 = "{(role: $x, role: $y);$y isa baseRoleEntity;$x isa baseRoleEntity;}";
        String query5 = "{(baseRole1: $x, baseRole2: $y);$x isa baseRoleEntity;}";
        String query6 = "{(baseRole1: $x, baseRole2: $y);$y isa baseRoleEntity;}";
        String query7 = "{(baseRole1: $x, baseRole2: $y);$x isa baseRoleEntity;$y isa subRoleEntity;}";
        String query8 = "{(baseRole1: $x, baseRole2: $y);$x isa baseRoleEntity;$y isa baseRoleEntity;}";

        ArrayList<String> queries = Lists.newArrayList(query, query2, query3, query4, query5, query6, query7, query8);

        equivalence(query, queries, Collections.singletonList(query2), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query, queries, Collections.singletonList(query2), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query2, queries, Collections.singletonList(query), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query2, queries, Collections.singletonList(query), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query3, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query3, queries, new ArrayList<>(), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query4, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query4, queries, new ArrayList<>(), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query5, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query5, queries, new ArrayList<>(), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query6, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query6, queries, new ArrayList<>(), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query7, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query7, queries, new ArrayList<>(), ReasonerQueryEquivalence.StructuralEquivalence, tx);
    }

    @Test
    public void testEquivalence_RelationsWithSubstitution(){
        String query = "{(role: $x, role: $y);$x id 'V666';}";
        String queryb = "{(role: $x, role: $y);$y id 'V666';}";

        String query2 = "{(role: $x, role: $y);$x != $y;}";
        String query3 = "{(role: $x, role: $y);$x != $y;$y id 'V667';}";

        String query4 = "{(role: $x, role: $y);$x id 'V666';$y id 'V667';}";
        String query4b = "{(role: $x, role: $y);$y id 'V666';$x id 'V667';}";

        String query7 = "{(role: $x, role: $y);$x id 'V666';$y id 'V666';}";

        String query5 = "{(baseRole1: $x, baseRole2: $y);$x id 'V666';$y id 'V667';}";
        String query6 = "{(baseRole1: $x, baseRole2: $y);$y id 'V666';$x id 'V667';}";

        ArrayList<String> queries = Lists.newArrayList(query, queryb, query2, query3, query4, query4b, query5, query6, query7);

        equivalence(query, queries, Collections.singletonList(queryb), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query, queries, Collections.singletonList(queryb), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(queryb, queries, Collections.singletonList(query), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(queryb, queries, Collections.singletonList(query), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query2, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query2, queries, new ArrayList<>(), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query3, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query3, queries, new ArrayList<>(), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query4, queries, Collections.singletonList(query4b), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query4, queries, Lists.newArrayList(query4b, query7), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query4b, queries, Collections.singletonList(query4), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query4b, queries, Lists.newArrayList(query4, query7), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query5, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query5, queries, Collections.singletonList(query6), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query6, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query6, queries, Collections.singletonList(query5), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query7, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query7, queries, Lists.newArrayList(query4, query4b), ReasonerQueryEquivalence.StructuralEquivalence, tx);
    }

    @Test
    public void testEquivalence_RelationsWithSubstitution_differentRolesMapped(){
        String query = "{(baseRole1: $x, baseRole2: $y);$x id 'V666';}";
        String query2 = "{(baseRole1: $x, baseRole2: $y);$x id 'V667';}";
        String query3 = "{(baseRole1: $x, baseRole2: $y);$y id 'V666';}";
        String query4 = "{(baseRole1: $x, baseRole2: $y);$y id 'V667';}";

        String query5 = "{(baseRole1: $x, baseRole2: $y);$x != $y;}";
        String query6 = "{(baseRole1: $x, baseRole2: $y);$x != $x2;}";
        String query7 = "{(baseRole1: $x, baseRole2: $y);$x != $x2;$x2 id 'V667';}";

        String query8 = "{(baseRole1: $x, baseRole2: $y);$x id 'V666';$y id 'V667';}";
        String query9 = "{(baseRole1: $x, baseRole2: $y);$y id 'V666';$x id 'V667';}";
        ArrayList<String> queries = Lists.newArrayList(query, query2, query3, query4, query5, query6, query7, query9, query9);

        equivalence(query, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query, queries, Collections.singletonList(query2), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query2, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query2, queries, Collections.singletonList(query), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query3, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query3, queries, Collections.singletonList(query4), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query4, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query4, queries, Collections.singletonList(query3), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query5, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query5, queries, new ArrayList<>(), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query6, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query6, queries, new ArrayList<>(), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query7, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query7, queries, new ArrayList<>(), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query8, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query8, queries, Collections.singletonList(query9), ReasonerQueryEquivalence.StructuralEquivalence, tx);
    }

    @Test
    public void testEquivalence_ResourcesAsRoleplayers(){
        String query = "{(baseRole1: $x, baseRole2: $y);$x == 'V666';}";
        String query2 = "{(baseRole1: $x, baseRole2: $y);$x == 'V667';}";

        String query3 = "{(baseRole1: $x, baseRole2: $y);$y == 'V666';}";
        String query4 = "{(baseRole1: $x, baseRole2: $y);$y == 'V667';}";

        String query5 = "{(baseRole1: $x, baseRole2: $y);$x !== $y;}";
        String query6 = "{(baseRole1: $x, baseRole2: $y);$x !== $x2;}";

        //TODO
        //String query7 = "{(baseRole1: $x, baseRole2: $y);$x !== $x2;$x2 == 'V667';}";

        String query8 = "{(baseRole1: $x, baseRole2: $y);$x == 'V666';$y == 'V667';}";
        String query9 = "{(baseRole1: $x, baseRole2: $y);$y == 'V666';$x == 'V667';}";
        ArrayList<String> queries = Lists.newArrayList(query, query2, query3, query4, query5, query6,  query9, query9);

        equivalence(query, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query, queries, new ArrayList<>(), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query2, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query2, queries, new ArrayList<>(), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query3, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query3, queries, new ArrayList<>(), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query4, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query4, queries, new ArrayList<>(), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query5, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query5, queries, new ArrayList<>(), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query6, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query6, queries, new ArrayList<>(), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        //equivalence(query7, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        //equivalence(query7, queries, new ArrayList<>(), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query8, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query8, queries, new ArrayList<>(), ReasonerQueryEquivalence.StructuralEquivalence, tx);
    }

    @Test
    public void testEquivalence_RelationsWithVariableAndSubstitution(){
        String query = "{$r (baseRole1: $x);$x id 'V666';}";
        String query2 = "{$a (baseRole1: $x);$x id 'V667';}";
        String query3 = "{$b (baseRole2: $y);$y id 'V666';}";
        String query4 = "{$c (baseRole1: $a);$a != $b;}";
        String query4b = "{$r (baseRole1: $x);$x != $y;}";
        String query5 = "{$e (baseRole1: $a);$a != $b;$b id 'V666';}";
        String query5b = "{$r (baseRole1: $x);$x != $y;$y id 'V666';}";
        ArrayList<String> queries = Lists.newArrayList(query, query2, query3, query4, query4b, query5, query5b);

        equivalence(query, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query, queries, Collections.singletonList(query2), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query2, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query2, queries, Collections.singletonList(query), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query3, queries, new ArrayList<>(), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query3, queries, new ArrayList<>(), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query4, queries, Collections.singletonList(query4b), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query4, queries, Collections.singletonList(query4b), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query4b, queries, Collections.singletonList(query4), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query4b, queries, Collections.singletonList(query4), ReasonerQueryEquivalence.StructuralEquivalence, tx);

        equivalence(query5, queries, Collections.singletonList(query5b), ReasonerQueryEquivalence.AlphaEquivalence, tx);
        equivalence(query5, queries, Collections.singletonList(query5b), ReasonerQueryEquivalence.StructuralEquivalence, tx);
    }

    private void equivalence(String target, List<String> queries, List<String> equivalentQueries, ReasonerQueryEquivalence equiv, EmbeddedGraknTx tx){
        queries.forEach(q -> equivalence(target, q, equivalentQueries.contains(q) || q.equals(target), equiv, tx));
    }

    private void equivalence(String patternA, String patternB, boolean expectation, ReasonerQueryEquivalence equiv, EmbeddedGraknTx tx){
        ReasonerAtomicQuery a = ReasonerQueries.atomic(conjunction(patternA), tx);
        ReasonerAtomicQuery b = ReasonerQueries.atomic(conjunction(patternB), tx);
        queryEquivalence(a, b, expectation, equiv);
        atomicEquivalence(a.getAtom(), b.getAtom(), expectation, equiv.atomicEquivalence());
    }


    private void queryEquivalence(ReasonerAtomicQuery a, ReasonerAtomicQuery b, boolean queryExpectation, ReasonerQueryEquivalence equiv){
        singleQueryEquivalence(a, a, true, equiv);
        singleQueryEquivalence(b, b, true, equiv);
        singleQueryEquivalence(a, b, queryExpectation, equiv);
        singleQueryEquivalence(b, a, queryExpectation, equiv);
    }

    private void atomicEquivalence(Atomic a, Atomic b, boolean expectation, AtomicEquivalence equiv){
        singleAtomicEquivalence(a, a, true, equiv);
        singleAtomicEquivalence(b, b, true, equiv);
        singleAtomicEquivalence(a, b, expectation, equiv);
        singleAtomicEquivalence(b, a, expectation, equiv);
    }

    private void singleQueryEquivalence(ReasonerAtomicQuery a, ReasonerAtomicQuery b, boolean queryExpectation, ReasonerQueryEquivalence equiv){
        assertEquals(equiv.name() + " - Query: " + a.toString() + " =? " + b.toString(), queryExpectation, equiv.equivalent(a, b));

        //check hash additionally if need to be equal
        if (queryExpectation) {
            assertEquals(a.toString() + " hash=? " + b.toString(), true, equiv.hash(a) == equiv.hash(b));
        }
    }

    private void singleAtomicEquivalence(Atomic a, Atomic b, boolean expectation, AtomicEquivalence equivalence){
        assertEquals(equivalence.name() + " - Atom: " + a.toString() + " =? " + b.toString(), expectation,  equivalence.equivalent(a, b));

        //check hash additionally if need to be equal
        if (expectation) {
            assertEquals(a.toString() + " hash=? " + b.toString(), equivalence.hash(a) == equivalence.hash(b), true);
        }
    }

    private Conjunction<VarPatternAdmin> conjunction(String patternString){
        Set<VarPatternAdmin> vars = Graql.parser().parsePattern(patternString).admin()
                .getDisjunctiveNormalForm().getPatterns()
                .stream().flatMap(p -> p.getPatterns().stream()).collect(toSet());
        return Patterns.conjunction(vars);
    }

}