/*
 * Copyright 2015-2024 the original author or authors.
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v2.0 which
 * accompanies this distribution and is available at
 *
 * https://www.eclipse.org/legal/epl-v20.html
 */

package org.junit.platform.engine.support.hierarchical;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Comparator.comparing;
import static java.util.Comparator.naturalOrder;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;
import static org.junit.platform.commons.util.CollectionUtils.getOnlyElement;
import static org.junit.platform.engine.support.hierarchical.ExclusiveResource.GLOBAL_KEY;
import static org.junit.platform.engine.support.hierarchical.ExclusiveResource.GLOBAL_READ;
import static org.junit.platform.engine.support.hierarchical.ExclusiveResource.GLOBAL_READ_WRITE;
import static org.junit.platform.engine.support.hierarchical.ExclusiveResource.LockMode.READ;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.junit.platform.engine.support.hierarchical.SingleLock.GlobalReadLock;
import org.junit.platform.engine.support.hierarchical.SingleLock.GlobalReadWriteLock;

/**
 * @since 1.3
 */
class LockManager {

	private static final Comparator<ExclusiveResource> COMPARATOR //
		= comparing(ExclusiveResource::getKey, globalKeyFirst().thenComparing(naturalOrder())) //
				.thenComparing(ExclusiveResource::getLockMode);

	private static Comparator<String> globalKeyFirst() {
		return comparing(key -> !GLOBAL_KEY.equals(key));
	}

	private final Map<String, ReadWriteLock> locksByKey = new ConcurrentHashMap<>();
	private final GlobalReadLock globalReadLock;
	private final GlobalReadWriteLock globalReadWriteLock;

	public LockManager() {
		globalReadLock = new GlobalReadLock(toLock(GLOBAL_READ));
		globalReadWriteLock = new GlobalReadWriteLock(toLock(GLOBAL_READ_WRITE));
	}

	ResourceLock getLockForResources(Collection<ExclusiveResource> resources) {
		return toResourceLock(toDistinctSortedLocks(resources));
	}

	ResourceLock getLockForResource(ExclusiveResource resource) {
		return toResourceLock(toLock(resource));
	}

	private List<Lock> toDistinctSortedLocks(Collection<ExclusiveResource> resources) {
		if (resources.isEmpty()) {
			return emptyList();
		}
		if (resources.size() == 1) {
			return singletonList(toLock(getOnlyElement(resources)));
		}
		// @formatter:off
		Map<String, List<ExclusiveResource>> resourcesByKey = resources.stream()
				.sorted(COMPARATOR)
				.distinct()
				.collect(groupingBy(ExclusiveResource::getKey, LinkedHashMap::new, toList()));

		return resourcesByKey.values().stream()
				.map(resourcesWithSameKey -> resourcesWithSameKey.get(0))
				.map(this::toLock)
				.collect(toList());
		// @formatter:on
	}

	private Lock toLock(ExclusiveResource resource) {
		ReadWriteLock lock = this.locksByKey.computeIfAbsent(resource.getKey(), key -> new ReentrantReadWriteLock());
		return resource.getLockMode() == READ ? lock.readLock() : lock.writeLock();
	}

	private ResourceLock toResourceLock(List<Lock> locks) {
		switch (locks.size()) {
			case 0:
				return NopLock.INSTANCE;
			case 1:
				return toResourceLock(locks.get(0));
			default:
				return new CompositeLock(locks);
		}
	}

	private ResourceLock toResourceLock(Lock lock) {
		if (lock == toLock(GLOBAL_READ)) {
			return globalReadLock;
		}
		if (lock == toLock(GLOBAL_READ_WRITE)) {
			return globalReadWriteLock;
		}
		return new SingleLock(lock);
	}

}
