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
package org.burningwave.core.classes;

import static org.burningwave.core.assembler.StaticComponentContainer.BackgroundExecutor;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.burningwave.core.classes.ClassCriteria.TestContext;
import org.burningwave.core.io.FileSystemItem;
import org.burningwave.core.io.PathHelper;
import org.burningwave.core.iterable.Properties;

public class ByteCodeHunter extends ClassPathScannerWithCachingSupport<JavaClass, SearchContext<JavaClass>, ByteCodeHunter.SearchResult> {
	
	private ByteCodeHunter(
		Supplier<ClassHunter> classHunterSupplier,
		PathHelper pathHelper,
		Properties config
	) {
		super(
			classHunterSupplier,
			pathHelper,
			(initContext) -> SearchContext.<JavaClass>create(
				initContext
			),
			(context) -> new ByteCodeHunter.SearchResult(context),
			config
		);
	}
	
	public static ByteCodeHunter create(
		Supplier<ClassHunter> classHunterSupplier, 
		PathHelper pathHelper,
		Properties config
	) {
		return new ByteCodeHunter(classHunterSupplier, pathHelper, config);
	}
	
	@Override
	<S extends SearchConfigAbst<S>> ClassCriteria.TestContext testClassCriteria(SearchContext<JavaClass> context, JavaClass javaClass) {
		return context.getSearchConfig().getClassCriteria().hasNoPredicate() ?
			context.getSearchConfig().getClassCriteria().testWithTrueResultForNullEntityOrTrueResultForNullPredicate(null) :
			super.testClassCriteria(context, javaClass);
	}
	
	@Override
	<S extends SearchConfigAbst<S>> ClassCriteria.TestContext testCachedItem(SearchContext<JavaClass> context, String path, String key, JavaClass javaClass) {
		return context.getSearchConfig().getClassCriteria().hasNoPredicate() ?
			context.getSearchConfig().getClassCriteria().testWithTrueResultForNullEntityOrTrueResultForNullPredicate(null) :				
			super.testClassCriteria(context, javaClass);
	}
	
	@Override
	void addToContext(SearchContext<JavaClass> context, TestContext criteriaTestContext,
		String basePath, FileSystemItem fileSystemItem, JavaClass javaClass
	) {
		context.addItemFound(basePath, fileSystemItem.getAbsolutePath(), javaClass);		
	}
	
	@Override
	void analyze(SearchContext<JavaClass> context, FileSystemItem child, FileSystemItem basePath) {
		JavaClass javaClass = JavaClass.create(child.toByteBuffer());
		ClassCriteria.TestContext criteriaTestContext = testClassCriteria(context, javaClass);
		if (criteriaTestContext.getResult()) {
			addToContext(
				context, criteriaTestContext, basePath.getAbsolutePath(), child, javaClass
			);
		}
	}
	
	@Override
	void clearItemsForPath(Map<String, JavaClass> items) {
		BackgroundExecutor.add(() -> {
			Iterator<Entry<String, JavaClass>> itr = items.entrySet().iterator();
			while (itr.hasNext()) {
				Entry<String, JavaClass> item = itr.next();
				itr.remove();
				item.getValue().close();
			}
		});
	}
	
	public static class SearchResult extends org.burningwave.core.classes.SearchResult<JavaClass> {

		public SearchResult(SearchContext<JavaClass> context) {
			super(context);
		}
		
		public Collection<JavaClass> getClasses() {
			return context.getItemsFound();
		}
		
		public Map<String, JavaClass> getClassesFlatMap() {
			return context.getItemsFoundFlatMap();
		}
		
		public Map<String, ByteBuffer> getByteCodesFlatMap() {
			return getClassesFlatMap().entrySet().stream().collect(
				Collectors.toMap(e ->
					e.getValue().getName(), e -> e.getValue().getByteCode())
			);
		}
	}
}
