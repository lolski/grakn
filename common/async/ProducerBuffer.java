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

package grakn.core.common.async;

import grakn.common.collection.Either;
import grakn.core.common.concurrent.ManagedBlockingQueue;
import grakn.core.common.exception.GraknException;
import grakn.core.common.iterator.ResourceIterator;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static grakn.core.common.concurrent.ExecutorService.PARALLELISATION_FACTOR;

public class ProducerBuffer<T> {

    private static final int BUFFER_MIN_SIZE = 32;
    private static final int BUFFER_MAX_SIZE = 64;

    private final ConcurrentLinkedQueue<Producer<T>> producers;
    private final ManagedBlockingQueue<Either<T, Done>> queue;
    private final Iterator iterator;
    private final Sink sink;
    private final AtomicInteger pending;
    private final int parallelisation;
    private final int bufferMinSize;
    private final int bufferMaxSize;

    static class Done {}

    public ProducerBuffer(List<Producer<T>> producers) {
        this(producers, PARALLELISATION_FACTOR, BUFFER_MIN_SIZE, BUFFER_MAX_SIZE);
    }

    public ProducerBuffer(List<Producer<T>> producers, int parallelisation) {
        this(producers, parallelisation, BUFFER_MIN_SIZE, BUFFER_MAX_SIZE);
    }

    public ProducerBuffer(List<Producer<T>> producers, int bufferMinSize, int bufferMaxSize) {
        this(producers, PARALLELISATION_FACTOR, bufferMinSize, bufferMaxSize);
    }

    public ProducerBuffer(List<Producer<T>> producers, int parallelisation, int bufferMinSize, int bufferMaxSize) {
        this.producers = new ConcurrentLinkedQueue<>(producers);
        this.parallelisation = parallelisation;
        this.queue = new ManagedBlockingQueue<>();
        this.iterator = new Iterator();
        this.sink = new Sink();
        this.pending = new AtomicInteger(0);
        this.bufferMinSize = bufferMinSize;
        this.bufferMaxSize = bufferMaxSize;
    }

    public ProducerBuffer<T>.Iterator iterator() {
        return iterator;
    }

    public void mayProduce() {
        int available = bufferMaxSize - queue.size() - pending.get();
        if (available > bufferMaxSize - bufferMinSize) {
            assert !producers.isEmpty();
            // TODO: should we call this method asynchronously?
            producers.peek().produce(available, parallelisation, sink);
            pending.addAndGet(available);
        }
    }

    private enum State {EMPTY, FETCHED, COMPLETED}

    public class Iterator implements ResourceIterator<T> {

        private T next;
        private State state;

        Iterator() {
            state = State.EMPTY;
        }

        @Override
        public boolean hasNext() {
            if (state == State.COMPLETED) return false;
            else if (state == State.FETCHED) return true;
            else mayProduce();

            Either<T, Done> result;
            try {
                result = queue.take();
            } catch (InterruptedException e) {
                throw GraknException.of(e);
            }

            if (result.isFirst()) {
                next = result.first();
                state = State.FETCHED;
            } else {
                recycle();
                state = State.COMPLETED;
            }

            return state == State.FETCHED;
        }

        @Override
        public T next() {

            if (!hasNext()) throw new NoSuchElementException();
            state = State.EMPTY;
            return next;
        }

        @Override
        public void recycle() {
            producers.forEach(Producer::recycle);
        }
    }

    public class Sink implements Producer.Sink<T> {

        public void put(T item) {
            try {
                queue.put(Either.first(item));
                pending.decrementAndGet();
            } catch (InterruptedException e) {
                throw GraknException.of(e);
            }
        }

        public void done() {
            assert !producers.isEmpty();
            producers.remove();

            if (producers.isEmpty()) {
                try {
                    queue.put(Either.second(new Done()));
                } catch (InterruptedException e) {
                    throw GraknException.of(e);
                }
            }
        }
    }
}
