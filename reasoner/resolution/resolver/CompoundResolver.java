/*
 * Copyright (C) 2021 Vaticle
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

package com.vaticle.typedb.core.reasoner.resolution.resolver;

import com.vaticle.typedb.core.concept.ConceptManager;
import com.vaticle.typedb.core.reasoner.resolution.ResolverRegistry;
import com.vaticle.typedb.core.reasoner.resolution.framework.Request;
import com.vaticle.typedb.core.reasoner.resolution.framework.Resolver;
import com.vaticle.typedb.core.reasoner.resolution.framework.Response;
import com.vaticle.typedb.core.traversal.TraversalEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;

public abstract class CompoundResolver<
        RESOLVER extends CompoundResolver<RESOLVER, REQ_STATE>,
        REQ_STATE extends CompoundResolver.RequestState
        > extends Resolver<RESOLVER> {

    private static final Logger LOG = LoggerFactory.getLogger(CompoundResolver.class);

    final Map<Request, REQ_STATE> requestStates;
    boolean isInitialised;

    protected CompoundResolver(Driver<RESOLVER> driver, String name, ResolverRegistry registry,
                               TraversalEngine traversalEngine, ConceptManager conceptMgr, boolean resolutionTracing) {
        super(driver, name, registry, traversalEngine, conceptMgr, resolutionTracing);
        this.requestStates = new HashMap<>();
        this.isInitialised = false;
    }

    protected abstract void nextAnswer(Request fromUpstream, REQ_STATE requestState, int iteration);

    @Override
    public void receiveRequest(Request fromUpstream, int iteration) {
        LOG.trace("{}: received Request: {}", name(), fromUpstream);
        if (!isInitialised) initialiseDownstreamResolvers();
        if (isTerminated()) return;

        REQ_STATE requestState = getOrUpdateRequestState(fromUpstream, iteration);
        if (iteration < requestState.iteration()) {
            // short circuit if the request came from a prior iteration
            failToUpstream(fromUpstream, iteration);
        } else {
            assert iteration == requestState.iteration();
            nextAnswer(fromUpstream, requestState, iteration);
        }
    }

    @Override
    protected void receiveFail(Response.Fail fromDownstream, int iteration) {
        LOG.trace("{}: received Exhausted, with iter {}: {}", name(), iteration, fromDownstream);
        if (isTerminated()) return;

        Request toDownstream = fromDownstream.sourceRequest();
        Request fromUpstream = fromUpstream(toDownstream);
        REQ_STATE requestState = requestStates.get(fromUpstream);

        if (iteration < requestState.iteration()) {
            // short circuit old iteration failed messages back out of the actor model
            failToUpstream(fromUpstream, iteration);
            return;
        }
        requestState.removeDownstreamProducer(fromDownstream.sourceRequest());
        nextAnswer(fromUpstream, requestState, iteration);
    }

    private REQ_STATE getOrUpdateRequestState(Request fromUpstream, int iteration) {
        if (!requestStates.containsKey(fromUpstream)) {
            requestStates.put(fromUpstream, requestStateCreate(fromUpstream, iteration));
        } else {
            REQ_STATE requestState = requestStates.get(fromUpstream);

            if (requestState.iteration() < iteration) {
                // when the same request for the next iteration the first time, re-initialise required state
                REQ_STATE responseProducerNextIter = requestStateReiterate(fromUpstream, requestState, iteration);
                this.requestStates.put(fromUpstream, responseProducerNextIter);
            }
        }
        return requestStates.get(fromUpstream);
    }

    abstract REQ_STATE requestStateCreate(Request fromUpstream, int iteration);

    abstract REQ_STATE requestStateReiterate(Request fromUpstream, REQ_STATE priorResponses, int iteration);

    static class RequestState {

        private final int iteration;
        private Iterator<Request> downstreamProducerSelector;
        final LinkedHashSet<Request> downstreamProducer;

        public RequestState(int iteration) {
            this.iteration = iteration;
            downstreamProducer = new LinkedHashSet<>();
            downstreamProducerSelector = downstreamProducer.iterator();
        }

        public boolean hasDownstreamProducer() {
            return !downstreamProducer.isEmpty();
        }

        public Request nextDownstreamProducer() {
            if (!downstreamProducerSelector.hasNext()) downstreamProducerSelector = downstreamProducer.iterator();
            return downstreamProducerSelector.next();
        }

        public void addDownstreamProducer(Request request) {
            assert !(downstreamProducer.contains(request)) : "downstream answer producer already contains this request";

            downstreamProducer.add(request);
            downstreamProducerSelector = downstreamProducer.iterator();
        }

        public void removeDownstreamProducer(Request request) {
            boolean removed = downstreamProducer.remove(request);
            // only update the iterator when removing an element, to avoid resetting and reusing first request too often
            // note: this is a large performance win when processing large batches of requests
            if (removed) downstreamProducerSelector = downstreamProducer.iterator();
        }

        public int iteration() {
            return iteration;
        }
    }
}
