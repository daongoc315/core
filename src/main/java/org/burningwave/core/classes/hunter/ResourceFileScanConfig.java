/*
 * This file is part of Burningwave Core.
 *
 * Author: Roberto Gentili
 *
 * Hosted at: https://github.com/burningwave/core
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Roberto Gentili
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.burningwave.core.classes.hunter;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ResourceFileScanConfig extends FileScanConfig<ResourceFileScanConfig> {
	private final static Predicate<String> ARCHIVE_PREDICATE = name -> 
		name.endsWith(".jar") ||
		name.endsWith(".war") ||
		name.endsWith(".ear") ||
		name.endsWith(".zip");
	private final static Predicate<String> RESOURCE_PREDICATE = ARCHIVE_PREDICATE.negate().and(name -> !name.endsWith(".class"));
	private final static Predicate<String> RESOURCE_PREDICATE_FOR_ZIP_ENTRY = RESOURCE_PREDICATE.and(name -> !name.endsWith("/"));
	
	private static ResourceFileScanConfig create() {
		return new ResourceFileScanConfig();
	}	

	@Override
	ResourceFileScanConfig _create() {
		return create();
	}
	
	public static ResourceFileScanConfig forPaths(Collection<String> paths) {
		ResourceFileScanConfig criteria = create();
		criteria.paths.addAll(paths);
		return criteria;
	}			
	
	public static ResourceFileScanConfig forPaths(String... paths) {
		return forPaths(Stream.of(paths).collect(Collectors.toCollection(ConcurrentHashMap::newKeySet)));
	}
	@Override
	Predicate<String> getClassPredicateForFileSystem() {
		return RESOURCE_PREDICATE;
	}

	@Override
	Predicate<String> getArchivePredicateForFileSystem() {
		return ARCHIVE_PREDICATE;
	}

	@Override
	Predicate<String> getClassPredicateForZipEntry() {
		return RESOURCE_PREDICATE_FOR_ZIP_ENTRY;
	}

	@Override
	Predicate<String> getArchivePredicateForZipEntry() {
		return ARCHIVE_PREDICATE;
	}
}