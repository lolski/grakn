/*
 * Copyright (C) 2020 Grakn Labs
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
 *
 */

package hypergraph.concept.thing.impl;

import hypergraph.concept.thing.Attribute;
import hypergraph.concept.type.impl.AttributeTypeImpl;
import hypergraph.graph.util.Schema;
import hypergraph.graph.vertex.ThingVertex;

public class AttributeImpl extends ThingImpl implements Attribute {

    public AttributeImpl(ThingVertex vertex) {
        super(vertex);
        assert vertex.schema().equals(Schema.Vertex.Thing.ATTRIBUTE);
    }

    @Override
    public AttributeTypeImpl type() {
        return AttributeTypeImpl.of(vertex.typeVertex());
    }

    @Override
    public AttributeImpl has(Attribute attribute) {
        return null; //TODO
    }

    @Override
    public Object value() {
        return vertex.value();
    }

    public static class Boolean extends AttributeImpl implements Attribute.Boolean {

        public Boolean(ThingVertex vertex) {
            super(vertex);
            assert vertex.typeVertex().valueType().equals(Schema.ValueType.BOOLEAN);
        }

        @Override
        public java.lang.Boolean value() {
            return (java.lang.Boolean) vertex.value();
        }
    }

    public static class Long extends AttributeImpl implements Attribute.Long {

        public Long(ThingVertex vertex) {
            super(vertex);
            assert vertex.typeVertex().valueType().equals(Schema.ValueType.LONG);
        }

        @Override
        public java.lang.Long value() {
            return (java.lang.Long) vertex.value();
        }
    }

    public static class Double extends AttributeImpl implements Attribute.Double {

        public Double(ThingVertex vertex) {
            super(vertex);
            assert vertex.typeVertex().valueType().equals(Schema.ValueType.DOUBLE);
        }

        @Override
        public java.lang.Double value() {
            return (java.lang.Double) vertex.value();
        }
    }

    public static class String extends AttributeImpl implements Attribute.String {

        public String(ThingVertex vertex) {
            super(vertex);
            assert vertex.typeVertex().valueType().equals(Schema.ValueType.STRING);
        }

        @Override
        public java.lang.String value() {
            return (java.lang.String) vertex.value();
        }
    }

    public static class DateTime extends AttributeImpl implements Attribute.DateTime {

        public DateTime(ThingVertex vertex) {
            super(vertex);
            assert vertex.typeVertex().valueType().equals(Schema.ValueType.DATETIME);
        }

        @Override
        public java.time.LocalDateTime value() {
            return (java.time.LocalDateTime) vertex.value();
        }
    }
}
