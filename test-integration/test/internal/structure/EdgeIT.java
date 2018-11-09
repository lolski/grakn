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

package ai.grakn.kb.internal.structure;

import ai.grakn.GraknTxType;
import ai.grakn.concept.Entity;
import ai.grakn.factory.EmbeddedGraknSession;
import ai.grakn.kb.internal.EmbeddedGraknTx;
import ai.grakn.kb.internal.concept.EntityImpl;
import ai.grakn.kb.internal.concept.EntityTypeImpl;
import ai.grakn.test.rule.ConcurrentGraknServer;
import ai.grakn.util.Schema;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class EdgeIT {

    @ClassRule
    public static final ConcurrentGraknServer server = new ConcurrentGraknServer();

    private EmbeddedGraknTx tx;
    private EmbeddedGraknSession session;
    private EntityTypeImpl entityType;
    private EntityImpl entity;
    private EdgeElement edge;

    @Before
    public void setUp(){
        session = server.sessionWithNewKeyspace();
        tx = session.transaction(GraknTxType.WRITE);
        // Create Edge
        entityType = (EntityTypeImpl) tx.putEntityType("My Entity Type");
        entity = (EntityImpl) entityType.create();
        Edge tinkerEdge = tx.getTinkerTraversal().V().has(Schema.VertexProperty.ID.name(), entity.id().getValue()).outE().next();
        edge = new EdgeElement(tx, tinkerEdge);
    }

    @After
    public void tearDown(){
        tx.close();
        session.close();
    }

    @AfterClass
    public static void classTearDown() throws Exception {
        server.cleanup();
    }

    @Test
    public void checkEqualityBetweenEdgesBasedOnID() {
        Entity entity2 = entityType.create();
        Edge tinkerEdge = tx.getTinkerTraversal().V().has(Schema.VertexProperty.ID.name(), entity2.id().getValue()).outE().next();
        EdgeElement edge2 = new EdgeElement(tx, tinkerEdge);

        assertEquals(edge, edge);
        assertNotEquals(edge, edge2);
    }

    @Test
    public void whenGettingTheSourceOfAnEdge_ReturnTheConceptTheEdgeComesFrom() {
        assertEquals(entity.vertex(), edge.source());
    }

    @Test
    public void whenGettingTheTargetOfAnEdge_ReturnTheConceptTheEdgePointsTowards() {
        assertEquals(entityType.currentShard().vertex(), edge.target());
    }

    @Test
    public void whenGettingTheLabelOfAnEdge_ReturnExpectedType() {
        assertEquals(Schema.EdgeLabel.ISA.getLabel(), edge.label());
    }
}