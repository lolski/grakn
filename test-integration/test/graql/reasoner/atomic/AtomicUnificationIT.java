package ai.grakn.graql.internal.reasoner;

import ai.grakn.GraknSession;
import ai.grakn.GraknTx;
import ai.grakn.GraknTxType;
import ai.grakn.concept.Concept;
import ai.grakn.concept.Role;
import ai.grakn.factory.EmbeddedGraknSession;
import ai.grakn.graql.GetQuery;
import ai.grakn.graql.Query;
import ai.grakn.graql.Var;
import ai.grakn.graql.admin.Conjunction;
import ai.grakn.graql.admin.MultiUnifier;
import ai.grakn.graql.admin.Unifier;
import ai.grakn.graql.admin.VarPatternAdmin;
import ai.grakn.graql.answer.ConceptMap;
import ai.grakn.graql.internal.pattern.Patterns;
import ai.grakn.graql.internal.reasoner.atom.Atom;
import ai.grakn.graql.internal.reasoner.atom.binary.RelationshipAtom;
import ai.grakn.graql.internal.reasoner.query.ReasonerAtomicQuery;
import ai.grakn.graql.internal.reasoner.query.ReasonerQueries;
import ai.grakn.graql.internal.reasoner.rule.InferenceRule;
import ai.grakn.graql.internal.reasoner.unifier.UnifierImpl;
import ai.grakn.graql.internal.reasoner.unifier.UnifierType;
import ai.grakn.kb.internal.EmbeddedGraknTx;
import ai.grakn.test.rule.ConcurrentGraknServer;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
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
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static ai.grakn.graql.Graql.var;
import static ai.grakn.util.GraqlTestUtil.assertCollectionsEqual;
import static java.util.stream.Collectors.toSet;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("CheckReturnValue")
public class AtomicUnificationIT {

    @ClassRule
    public static final ConcurrentGraknServer server = new ConcurrentGraknServer();

    private static EmbeddedGraknSession genericSchemaSession;

    private static void loadFromFile(String fileName, GraknSession session){
        try {
            InputStream inputStream = AtomicUnificationIT.class.getClassLoader().getResourceAsStream("test-integration/test/graql/reasoner/resources/"+fileName);
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
    public void testUnification_RelationWithRolesExchanged(){
        String relation = "{(baseRole1: $x, baseRole2: $y) isa binary;}";
        String relation2 = "{(baseRole1: $y, baseRole2: $x) isa binary;}";
        exactUnification(relation, relation2, true, true, tx);
    }

    @Test
    public void testUnification_RelationWithMetaRole(){
        String relation = "{(baseRole1: $x, role: $y) isa binary;}";
        String relation2 = "{(baseRole1: $y, role: $x) isa binary;}";
        exactUnification(relation, relation2, true, true, tx);
    }

    @Test
    public void testUnification_RelationWithRelationVar(){
        String relation = "{$x (baseRole1: $r, baseRole2: $z) isa binary;}";
        String relation2 = "{$r (baseRole1: $x, baseRole2: $y) isa binary;}";
        exactUnification(relation, relation2, true, true, tx);
    }

    @Test
    public void testUnification_RelationWithMetaRolesAndIds(){
        Concept instance = tx.graql().<GetQuery>parse("match $x isa subRoleEntity; get;").execute().iterator().next().get(var("x"));
        String relation = "{(role: $x, role: $y) isa binary; $y id '" + instance.id().getValue() + "';}";
        String relation2 = "{(role: $z, role: $v) isa binary; $z id '" + instance.id().getValue() + "';}";
        String relation3 = "{(role: $z, role: $v) isa binary; $v id '" + instance.id().getValue() + "';}";

        exactUnification(relation, relation2, true, true, tx);
        exactUnification(relation, relation3, true, true, tx);
        exactUnification(relation2, relation3, true, true, tx);
    }

    @Test
    public void testUnification_BinaryRelationWithRoleHierarchy_ParentWithBaseRoles(){
        String parentRelation = "{(baseRole1: $x, baseRole2: $y);}";
        String specialisedRelation = "{(subRole1: $u, anotherSubRole2: $v);}";
        String specialisedRelation2 = "{(subRole1: $y, anotherSubRole2: $x);}";
        String specialisedRelation3 = "{(subSubRole1: $u, subSubRole2: $v);}";
        String specialisedRelation4 = "{(subSubRole1: $y, subSubRole2: $x);}";
        String specialisedRelation5 = "{(subRole1: $u, anotherSubRole1: $v);}";

        exactUnification(parentRelation, specialisedRelation, false, false, tx);
        exactUnification(parentRelation, specialisedRelation2, false, false, tx);
        exactUnification(parentRelation, specialisedRelation3, false, false, tx);
        exactUnification(parentRelation, specialisedRelation4, false, false, tx);
        nonExistentUnifier(parentRelation, specialisedRelation5, tx);
    }

    @Test
    public void testUnification_BinaryRelationWithRoleHierarchy_ParentWithSubRoles(){
        String parentRelation = "{(subRole1: $x, subRole2: $y);}";
        String specialisedRelation = "{(subRole1: $u, subSubRole2: $v);}";
        String specialisedRelation2 = "{(subRole1: $y, subSubRole2: $x);}";
        String specialisedRelation3 = "{(subSubRole1: $u, subSubRole2: $v);}";
        String specialisedRelation4 = "{(subSubRole1: $y, subSubRole2: $x);}";
        String specialisedRelation5 = "{(subSubRole1: $u, baseRole3: $v);}";
        String specialisedRelation6 = "{(baseRole1: $u, baseRole2: $v);}";

        exactUnification(parentRelation, specialisedRelation, false, false, tx);
        exactUnification(parentRelation, specialisedRelation2, false, false, tx);
        exactUnification(parentRelation, specialisedRelation3, false, false, tx);
        exactUnification(parentRelation, specialisedRelation4, false, false, tx);
        nonExistentUnifier(parentRelation, specialisedRelation5, tx);
        nonExistentUnifier(parentRelation, specialisedRelation6, tx);
    }

    @Test
    public void testUnification_TernaryRelationWithRoleHierarchy_ParentWithBaseRoles(){
        String parentRelation = "{(baseRole1: $x, baseRole2: $y, baseRole3: $z);}";
        String specialisedRelation = "{(baseRole1: $u, subRole2: $v, subSubRole3: $q);}";
        String specialisedRelation2 = "{(baseRole1: $z, subRole2: $y, subSubRole3: $x);}";
        String specialisedRelation3 = "{(subRole1: $u, subRole2: $v, subSubRole3: $q);}";
        String specialisedRelation4 = "{(subRole1: $y, subRole2: $z, subSubRole3: $x);}";
        String specialisedRelation5 = "{(subRole1: $u, subRole1: $v, subSubRole3: $q);}";

        exactUnification(parentRelation, specialisedRelation, false, true, tx);
        exactUnification(parentRelation, specialisedRelation2, false, true, tx);
        exactUnification(parentRelation, specialisedRelation3, false, false, tx);
        exactUnification(parentRelation, specialisedRelation4, false, false, tx);
        nonExistentUnifier(parentRelation, specialisedRelation5, tx);
    }

    @Test
    public void testUnification_TernaryRelationWithRoleHierarchy_ParentWithSubRoles(){
        String parentRelation = "{(subRole1: $x, subRole2: $y, subRole3: $z);}";
        String specialisedRelation = "{(baseRole1: $u, subRole2: $v, subSubRole3: $q);}";
        String specialisedRelation2 = "{(subRole1: $u, subRole2: $v, subSubRole3: $q);}";
        String specialisedRelation3 = "{(subRole1: $y, subRole2: $z, subSubRole3: $x);}";
        String specialisedRelation4 = "{(subSubRole1: $u, subRole2: $v, subSubRole3: $q);}";
        String specialisedRelation5 = "{(subSubRole1: $y, subRole2: $z, subSubRole3: $x);}";
        String specialisedRelation6 = "{(subRole1: $u, subRole1: $v, subSubRole3: $q);}";

        nonExistentUnifier(parentRelation, specialisedRelation, tx);
        exactUnification(parentRelation, specialisedRelation2, false, false, tx);
        exactUnification(parentRelation, specialisedRelation3, false, false, tx);
        exactUnification(parentRelation, specialisedRelation4, false, false, tx);
        exactUnification(parentRelation, specialisedRelation5, false, false, tx);
        nonExistentUnifier(parentRelation, specialisedRelation6, tx);
    }

    @Test
    public void testUnification_TernaryRelationWithRoleHierarchy_ParentWithBaseRoles_childrenRepeatRolePlayers(){
        String parentRelation = "{(baseRole1: $x, baseRole2: $y, baseRole3: $z);}";
        String specialisedRelation = "{(baseRole1: $u, subRole2: $u, subSubRole3: $q);}";
        String specialisedRelation2 = "{(baseRole1: $y, subRole2: $y, subSubRole3: $x);}";
        String specialisedRelation3 = "{(subRole1: $u, subRole2: $u, subSubRole3: $q);}";
        String specialisedRelation4 = "{(subRole1: $y, subRole2: $y, subSubRole3: $x);}";
        String specialisedRelation5 = "{(subRole1: $u, subRole1: $u, subSubRole3: $q);}";

        exactUnification(parentRelation, specialisedRelation, false, false, tx);
        exactUnification(parentRelation, specialisedRelation2, false, false, tx);
        exactUnification(parentRelation, specialisedRelation3, false, false, tx);
        exactUnification(parentRelation, specialisedRelation4, false, false, tx);
        nonExistentUnifier(parentRelation, specialisedRelation5, tx);
    }

    @Test
    public void testUnification_TernaryRelationWithRoleHierarchy_ParentWithBaseRoles_parentRepeatRolePlayers(){
        String parentRelation = "{(baseRole1: $x, baseRole2: $x, baseRole3: $y);}";
        String specialisedRelation = "{(baseRole1: $u, subRole2: $v, subSubRole3: $q);}";
        String specialisedRelation2 = "{(baseRole1: $z, subRole2: $y, subSubRole3: $x);}";
        String specialisedRelation3 = "{(subRole1: $u, subRole2: $v, subSubRole3: $q);}";
        String specialisedRelation4 = "{(subRole1: $y, subRole2: $y, subSubRole3: $x);}";
        String specialisedRelation5 = "{(subRole1: $u, subRole1: $v, subSubRole3: $q);}";

        exactUnification(parentRelation, specialisedRelation, false, false, tx);
        exactUnification(parentRelation, specialisedRelation2, false, false, tx);
        exactUnification(parentRelation, specialisedRelation3, false, false, tx);
        exactUnification(parentRelation, specialisedRelation4, false, false, tx);
        nonExistentUnifier(parentRelation, specialisedRelation5, tx);
    }

    @Test
    public void testUnification_VariousResourceAtoms(){
        String resource = "{$x has resource $r;$r 'f';}";
        String resource2 = "{$r has resource $x;$x 'f';}";
        String resource3 = "{$r has resource 'f';}";
        String resource4 = "{$x has resource $y via $r;$y 'f';}";
        String resource5 = "{$y has resource $r via $x;$r 'f';}";
        exactUnification(resource, resource2, true, true, tx);
        exactUnification(resource, resource3, true, true, tx);
        exactUnification(resource2, resource3, true, true, tx);
        exactUnification(resource4, resource5, true, true, tx);
    }

    @Test
    public void testUnification_VariousTypeAtoms(){
        String type = "{$x isa baseRoleEntity;}";
        String type2 = "{$y isa baseRoleEntity;}";
        String userDefinedType = "{$y isa $x;$x label 'baseRoleEntity';}";
        String userDefinedType2 = "{$u isa $v;$v label 'baseRoleEntity';}";

        exactUnification(type, type2, true, true, tx);
        exactUnification(userDefinedType, userDefinedType2, true, true, tx);
        //TODO user defined-generated test
        //exactUnification(type, userDefinedType, true, true, tx);
    }

    @Test
    public void testUnification_ParentHasFewerRelationPlayers() {
        String childString = "{(subRole1: $y, subRole2: $x) isa binary;}";
        String parentString = "{(subRole1: $x) isa binary;}";
        String parentString2 = "{(subRole2: $y) isa binary;}";

        ReasonerAtomicQuery childQuery = ReasonerQueries.atomic(conjunction(childString, tx), tx);
        ReasonerAtomicQuery parentQuery = ReasonerQueries.atomic(conjunction(parentString, tx), tx);
        ReasonerAtomicQuery parentQuery2 = ReasonerQueries.atomic(conjunction(parentString2, tx), tx);

        Atom childAtom = childQuery.getAtom();
        Atom parentAtom = parentQuery.getAtom();
        Atom parentAtom2 = parentQuery2.getAtom();

        List<ConceptMap> childAnswers = childQuery.getQuery().execute();
        List<ConceptMap> parentAnswers = parentQuery.getQuery().execute();
        List<ConceptMap> parentAnswers2 = parentQuery2.getQuery().execute();

        Unifier unifier = childAtom.getUnifier(parentAtom, UnifierType.EXACT);
        Unifier unifier2 = childAtom.getUnifier(parentAtom2, UnifierType.EXACT);

        assertCollectionsEqual(
                parentAnswers,
                childAnswers.stream()
                        .map(a -> a.unify(unifier))
                        .map(a -> a.project(parentQuery.getVarNames()))
                        .distinct()
                        .collect(Collectors.toList())
        );
        assertCollectionsEqual(
                parentAnswers2,
                childAnswers.stream()
                        .map(a -> a.unify(unifier2))
                        .map(a -> a.project(parentQuery2.getVarNames()))
                        .distinct()
                        .collect(Collectors.toList())
        );
    }

    @Test
    public void testUnification_ResourceWithIndirectValuePredicate(){
        String resource = "{$x has resource $r;$r == 'f';}";
        String resource2 = "{$r has resource $x;$x == 'f';}";
        String resource3 = "{$r has resource 'f';}";

        ReasonerAtomicQuery resourceQuery = ReasonerQueries.atomic(conjunction(resource, tx), tx);
        ReasonerAtomicQuery resourceQuery2 = ReasonerQueries.atomic(conjunction(resource2, tx), tx);
        ReasonerAtomicQuery resourceQuery3 = ReasonerQueries.atomic(conjunction(resource3, tx), tx);

        String type = "{$x isa resource;$x id '" + resourceQuery.getQuery().execute().iterator().next().get("r").id().getValue()  + "';}";
        ReasonerAtomicQuery typeQuery = ReasonerQueries.atomic(conjunction(type, tx), tx);
        Atom typeAtom = typeQuery.getAtom();

        Atom resourceAtom = resourceQuery.getAtom();
        Atom resourceAtom2 = resourceQuery2.getAtom();
        Atom resourceAtom3 = resourceQuery3.getAtom();

        Unifier unifier = resourceAtom.getUnifier(typeAtom, UnifierType.RULE);
        Unifier unifier2 = resourceAtom2.getUnifier(typeAtom, UnifierType.RULE);
        Unifier unifier3 = resourceAtom3.getUnifier(typeAtom, UnifierType.RULE);

        ConceptMap typeAnswer = typeQuery.getQuery().execute().iterator().next();
        ConceptMap resourceAnswer = resourceQuery.getQuery().execute().iterator().next();
        ConceptMap resourceAnswer2 = resourceQuery2.getQuery().execute().iterator().next();
        ConceptMap resourceAnswer3 = resourceQuery3.getQuery().execute().iterator().next();

        assertEquals(typeAnswer.get(var("x")), resourceAnswer.unify(unifier).get(var("x")));
        assertEquals(typeAnswer.get(var("x")), resourceAnswer2.unify(unifier2).get(var("x")));
        assertEquals(typeAnswer.get(var("x")), resourceAnswer3.unify(unifier3).get(var("x")));
    }

    @Test
    public void testRewriteAndUnification(){
        String parentString = "{$r (subRole1: $x) isa binary;}";
        Atom parentAtom = ReasonerQueries.atomic(conjunction(parentString, tx), tx).getAtom();
        Var parentVarName = parentAtom.getVarName();

        String childPatternString = "(subRole1: $x, subRole2: $y) isa binary";
        InferenceRule testRule = new InferenceRule(
                tx.putRule("Checking Rewrite & Unification",
                        tx.graql().parser().parsePattern(childPatternString),
                        tx.graql().parser().parsePattern(childPatternString)),
                tx)
                .rewrite(parentAtom);

        RelationshipAtom headAtom = (RelationshipAtom) testRule.getHead().getAtom();
        Var headVarName = headAtom.getVarName();

        Unifier unifier = Iterables.getOnlyElement(testRule.getMultiUnifier(parentAtom));
        Unifier correctUnifier = new UnifierImpl(
                ImmutableMap.of(
                        var("x"), var("x"),
                        headVarName, parentVarName)
        );

        assertTrue(unifier.containsAll(correctUnifier));

        Multimap<Role, Var> roleMap = roleSetMap(headAtom.getRoleVarMap());
        Collection<Var> wifeEntry = roleMap.get(tx.getRole("subRole1"));
        assertEquals(wifeEntry.size(), 1);
        assertEquals(wifeEntry.iterator().next(), var("x"));
    }

    @Test
    public void testUnification_MatchAllParentAtom(){
        String parentString = "{$r($a, $x);}";
        String childString = "{$rel (baseRole1: $z, baseRole2: $b) isa binary;}";
        Atom parent = ReasonerQueries.atomic(conjunction(parentString, tx), tx).getAtom();
        Atom child = ReasonerQueries.atomic(conjunction(childString, tx), tx).getAtom();

        MultiUnifier multiUnifier = child.getMultiUnifier(parent, UnifierType.RULE);
        Unifier correctUnifier = new UnifierImpl(
                ImmutableMap.of(
                        var("z"), var("a"),
                        var("b"), var("x"),
                        child.getVarName(), parent.getVarName())
        );
        Unifier correctUnifier2 = new UnifierImpl(
                ImmutableMap.of(
                        var("z"), var("x"),
                        var("b"), var("a"),
                        child.getVarName(), parent.getVarName())
        );
        assertEquals(multiUnifier.size(), 2);
        multiUnifier.forEach(u -> assertTrue(u.containsAll(correctUnifier) || u.containsAll(correctUnifier2)));
    }

    @Test
    public void testUnification_IndirectRoles(){
        VarPatternAdmin basePattern = var()
                .rel(var("baseRole1").label("subRole1"), var("y1"))
                .rel(var("baseRole2").label("subSubRole2"), var("y2"))
                .isa("binary")
                .admin();

        ReasonerAtomicQuery baseQuery = ReasonerQueries.atomic(Patterns.conjunction(Sets.newHashSet(basePattern)), tx);
        ReasonerAtomicQuery childQuery = ReasonerQueries
                .atomic(conjunction(
                        "{($r1: $x1, $r2: $x2) isa binary;" +
                                "$r1 label 'subRole1';" +
                                "$r2 label 'subSubRole2';}"
                        , tx), tx);
        ReasonerAtomicQuery parentQuery = ReasonerQueries
                .atomic(conjunction(
                        "{($R1: $x, $R2: $y) isa binary;" +
                                "$R1 label 'subRole1';" +
                                "$R2 label 'subSubRole2';}"
                        , tx), tx);
        exactUnification(parentQuery, childQuery, true, true);
        exactUnification(baseQuery, parentQuery, true, true);
        exactUnification(baseQuery, childQuery, true, true);
    }

    @Test
    public void testUnification_IndirectRoles_NoRelationType(){
        VarPatternAdmin basePattern = var()
                .rel(var("baseRole1").label("subRole1"), var("y1"))
                .rel(var("baseRole2").label("subSubRole2"), var("y2"))
                .admin();

        ReasonerAtomicQuery baseQuery = ReasonerQueries.atomic(Patterns.conjunction(Sets.newHashSet(basePattern)), tx);
        ReasonerAtomicQuery childQuery = ReasonerQueries
                .atomic(conjunction(
                        "{($r1: $x1, $r2: $x2);" +
                                "$r1 label 'subRole1';" +
                                "$r2 label 'subSubRole2';}"
                        , tx), tx);
        ReasonerAtomicQuery parentQuery = ReasonerQueries
                .atomic(conjunction(
                        "{($R1: $x, $R2: $y);" +
                                "$R1 label 'subRole1';" +
                                "$R2 label 'subSubRole2';}"
                        , tx), tx);
        exactUnification(parentQuery, childQuery, true, true);
        exactUnification(baseQuery, parentQuery, true, true);
        exactUnification(baseQuery, childQuery, true, true);
    }

    private void roleInference(String patternString, ImmutableSetMultimap<Role, Var> expectedRoleMAp, EmbeddedGraknTx<?> tx){
        RelationshipAtom atom = (RelationshipAtom) ReasonerQueries.atomic(conjunction(patternString, tx), tx).getAtom();
        Multimap<Role, Var> roleMap = roleSetMap(atom.getRoleVarMap());
        assertEquals(expectedRoleMAp, roleMap);

    }

    /**
     * checks that the child query is not unifiable with parent - a unifier does not exist
     * @param parentQuery parent query
     * @param childQuery child query
     */
    private void nonExistentUnifier(ReasonerAtomicQuery parentQuery, ReasonerAtomicQuery childQuery){
        Atom childAtom = childQuery.getAtom();
        Atom parentAtom = parentQuery.getAtom();
        assertTrue(childAtom.getMultiUnifier(parentAtom, UnifierType.EXACT).isEmpty());
    }

    private void nonExistentUnifier(String parentPatternString, String childPatternString, EmbeddedGraknTx<?> tx){
        nonExistentUnifier(
                ReasonerQueries.atomic(conjunction(parentPatternString, tx), tx),
                ReasonerQueries.atomic(conjunction(childPatternString, tx), tx)
        );
    }

    /**
     * checks the correctness and uniqueness of an exact unifier required to unify child query with parent
     * @param parentQuery parent query
     * @param childQuery child query
     * @param checkInverse flag specifying whether the inverse equality u^{-1}=u(parent, child) of the unifier u(child, parent) should be checked
     * @param checkEquality if true the parent and child answers will be checked for equality, otherwise they are checked for containment of child answers in parent
     */
    private void exactUnification(ReasonerAtomicQuery parentQuery, ReasonerAtomicQuery childQuery, boolean checkInverse, boolean checkEquality){
        Atom childAtom = childQuery.getAtom();
        Atom parentAtom = parentQuery.getAtom();

        Unifier unifier = childAtom.getMultiUnifier(parentAtom, UnifierType.EXACT).getUnifier();

        List<ConceptMap> childAnswers = childQuery.getQuery().execute();
        List<ConceptMap> unifiedAnswers = childAnswers.stream()
                .map(a -> a.unify(unifier))
                .filter(a -> !a.isEmpty())
                .collect(Collectors.toList());
        List<ConceptMap> parentAnswers = parentQuery.getQuery().execute();

        if (checkInverse) {
            Unifier unifier2 = parentAtom.getUnifier(childAtom, UnifierType.EXACT);
            assertEquals(unifier.inverse(), unifier2);
            assertEquals(unifier, unifier2.inverse());
        }

        assertTrue(!childAnswers.isEmpty());
        assertTrue(!unifiedAnswers.isEmpty());
        assertTrue(!parentAnswers.isEmpty());

        if (!checkEquality){
            assertTrue(parentAnswers.containsAll(unifiedAnswers));
        } else {
            assertCollectionsEqual(parentAnswers, unifiedAnswers);
            Unifier inverse = unifier.inverse();
            List<ConceptMap> parentToChild = parentAnswers.stream().map(a -> a.unify(inverse)).collect(Collectors.toList());
            assertCollectionsEqual(parentToChild, childAnswers);
        }
    }

    private void exactUnification(String parentPatternString, String childPatternString, boolean checkInverse, boolean checkEquality, EmbeddedGraknTx<?> tx){
        exactUnification(
                ReasonerQueries.atomic(conjunction(parentPatternString, tx), tx),
                ReasonerQueries.atomic(conjunction(childPatternString, tx), tx),
                checkInverse,
                checkEquality);
    }

    private Multimap<Role, Var> roleSetMap(Multimap<Role, Var> roleVarMap) {
        Multimap<Role, Var> roleMap = HashMultimap.create();
        roleVarMap.entries().forEach(e -> roleMap.put(e.getKey(), e.getValue()));
        return roleMap;
    }

    private Conjunction<VarPatternAdmin> conjunction(String patternString, EmbeddedGraknTx<?> tx){
        Set<VarPatternAdmin> vars = tx.graql().parser().parsePattern(patternString).admin()
                .getDisjunctiveNormalForm().getPatterns()
                .stream().flatMap(p -> p.getPatterns().stream()).collect(toSet());
        return Patterns.conjunction(vars);
    }
}
