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
package org.burningwave.core.concurrent;

import static org.burningwave.core.assembler.StaticComponentContainer.Throwables;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import org.burningwave.core.Component;
import org.burningwave.core.ManagedLogger;

@SuppressWarnings("unchecked")
public class QueuedTasksExecutor implements Component {
	private Map<String, Boolean> runOnlyOnceTemporaryTargets;
	private Collection<TaskAbst<?>> tasksQueue;
	private TaskAbst<?> currentTask;
	private Boolean supended;
	private Mutex.Manager mutexManager;
	Thread executor;
	private int defaultPriority;
	private long executedTasksCount;
	private boolean isDaemon;
	private String name;
	private Boolean terminated;
	private Runnable initializer;
	
	private QueuedTasksExecutor(String name, int defaultPriority, boolean isDaemon) {
		mutexManager = Mutex.Manager.create(this);
		tasksQueue = new CopyOnWriteArrayList<>();
		initializer = () -> {
			this.name = name;
			this.defaultPriority = defaultPriority;
			this.isDaemon = isDaemon;
			init0();
		};
		runOnlyOnceTemporaryTargets = new ConcurrentHashMap<>();
		init();
	}
	
	void init() {
		initializer.run();
	}
	
	void init0() {		
		supended = Boolean.FALSE;
		terminated = Boolean.FALSE;
		executedTasksCount = 0;
		executor = new Thread(() -> {
			while (!terminated) {
				if (!tasksQueue.isEmpty()) {
					Iterator<TaskAbst<?>> taskIterator = tasksQueue.iterator();
					while (taskIterator.hasNext()) {
						synchronized(mutexManager.getMutex("resumeCaller")) {
							try {
								if (supended) {
									mutexManager.getMutex("resumeCaller").wait();
									break;
								}
							} catch (InterruptedException exc) {
								logWarn("Exception occurred", exc);
							}
						}
						TaskAbst<?> task =	this.currentTask = taskIterator.next();
						tasksQueue.remove(task);
						int currentExecutablePriority = task.getPriority();
						if (executor.getPriority() != currentExecutablePriority) {
							executor.setPriority(currentExecutablePriority);
						}
						task.execute();
						if (executor.getPriority() != this.defaultPriority) {
							executor.setPriority(this.defaultPriority);
						}
						++executedTasksCount;
						if (executedTasksCount % 10000 == 0) {
							logInfo("Executed {} tasks", executedTasksCount);
						}
						synchronized(mutexManager.getMutex("suspensionCaller")) {
							mutexManager.getMutex("suspensionCaller").notifyAll();
						}
						if (terminated) {
							break;
						}
					}
				} else {
					synchronized(mutexManager.getMutex("executingFinishedWaiter")) {
						mutexManager.getMutex("executingFinishedWaiter").notifyAll();
					}
					synchronized(mutexManager.getMutex("executableCollectionFiller")) {
						if (tasksQueue.isEmpty()) {
							try {
								mutexManager.getMutex("executableCollectionFiller").wait();
							} catch (InterruptedException exc) {
								logWarn("Exception occurred", exc);
							}
						}
					}
				}
			}
		}, name);
		executor.setPriority(this.defaultPriority);
		executor.setDaemon(isDaemon);
		executor.start();
	}
	
	public static QueuedTasksExecutor create(String name, int initialPriority) {
		return create(name, initialPriority, false, false);
	}
	
	public static QueuedTasksExecutor create(String name, int initialPriority, boolean daemon, boolean undestroyable) {
		if (undestroyable) {
			String creatorClass = Thread.currentThread().getStackTrace()[2].getClassName();
			return new QueuedTasksExecutor(name, initialPriority, daemon) {
				
				@Override
				public void shutDown(boolean waitForTasksTermination) {
					super.shutDown(waitForTasksTermination);
				}
				
				@Override
				void closeResources() {
					if (!Thread.currentThread().getStackTrace()[4].getClassName().equals(creatorClass)) {	
						this.executor = null;
						init();
					} else {
						super.closeResources();
					}
				}
				
			};
		} else {
			return new QueuedTasksExecutor(name, initialPriority, daemon);
		}
	}
	
	public <T> ProducerTask<T> createTaskWithCurrentThreadPriority(Supplier<T> executable) {
		return createTask(executable, Thread.currentThread().getPriority());
	}
	
	public <T> ProducerTask<T> createTask(Supplier<T> executable) {
		return createTask(executable, this.defaultPriority);
	}
	
	public <T> ProducerTask<T> createTask(Supplier<T> executable, int priority) {
		ProducerTask<T> task = new ProducerTask<T>(executable, priority, this.executor) {
			public ProducerTask<T> addToQueue() {
				add(this);
				return this;
			};
		};
		return task;
	}
	
	public Task createTaskWithCurrentThreadPriority(Runnable executable) {
		return createTask(executable, Thread.currentThread().getPriority());
	}
	
	public Task createTask(Runnable executable) {
		return createTask(executable, this.defaultPriority);
	}
	
	public Task createTask(Runnable executable, int priority) {
		Task task = new Task(executable, priority, this.executor) {
			public Task addToQueue() {
				add(this);
				return this;
			};
		};
		return task;
	}

	<E, T extends TaskAbst<E>> T add(T task) {
		try {
			tasksQueue.add(task);
			synchronized(mutexManager.getMutex("executableCollectionFiller")) {
				mutexManager.getMutex("executableCollectionFiller").notifyAll();
			}
		} catch (Throwable exc) {
			logWarn("Exception occurred", exc);
		}
		return task;
	}
	
	public Task createTaskToRunOnlyOnceWithCurrentThreadPriority(Object target, String operationId, Supplier<Boolean> hasBeenExecutedChecker, Runnable executable) {
		return createTaskToRunOnlyOnce(target, operationId, hasBeenExecutedChecker, executable, Thread.currentThread().getPriority());
	}
	
	public Task createTaskToRunOnlyOnce(Object target, String operationId, Supplier<Boolean> hasBeenExecutedChecker, Runnable executable) {
		return createTaskToRunOnlyOnce(target, operationId, hasBeenExecutedChecker, executable, this.defaultPriority);
	}
	
	public Task createTaskToRunOnlyOnce(
		Object target,
		String operationId,
		Supplier<Boolean> hasBeenExecutedChecker,
		Runnable executable,
		int threadPriority
	) {	
		String taskId = target.getClass().getName() + "@" +  System.identityHashCode(target) + "->" + operationId;
		if (canBeExecuted(target, taskId, hasBeenExecutedChecker)) {
			return createTask(() -> {
				try {
					executable.run();
				} finally {
					runOnlyOnceTemporaryTargets.remove(taskId);
				}
			}, threadPriority);
		}
		return null;
	}
	
	public <T> ProducerTask<T> createTaskToRunOnlyOnceWithCurrentThreadPriority(Object target, String operationId, Supplier<Boolean> hasBeenExecutedChecker, Supplier<T> executable) {
		return createTaskToRunOnlyOnce(target, operationId, hasBeenExecutedChecker, executable, Thread.currentThread().getPriority());
	}
	
	public <T> ProducerTask<T> createTaskToRunOnlyOnce(Object target, String operationId, Supplier<Boolean> hasBeenExecutedChecker, Supplier<T> executable) {
		return createTaskToRunOnlyOnce(target, operationId, hasBeenExecutedChecker, executable, this.defaultPriority);
	}
	
	public <T> ProducerTask<T> createTaskToRunOnlyOnce(
		Object target,
		String operationId,
		Supplier<Boolean> hasBeenExecuted,
		Supplier<T> executable,
		int threadPriority
	) {	
		String taskId = target.getClass().getName() + "@" +  System.identityHashCode(target) + "->" + operationId;
		if (canBeExecuted(target, taskId, hasBeenExecuted)) {
			return createTask(() -> {
				try {
					return executable.get();
				} finally {
					runOnlyOnceTemporaryTargets.remove(taskId);
				}
			}, threadPriority);
		}
		return null;
	}

	boolean canBeExecuted(Object target, String taskId, Supplier<Boolean> hasBeenExecuted) {
		boolean execute = false;
		if (!hasBeenExecuted.get()) {
			Boolean isClosing = runOnlyOnceTemporaryTargets.get(taskId);
			if (isClosing == null) {
				synchronized (target) {
					if (!hasBeenExecuted.get()) {
						isClosing = runOnlyOnceTemporaryTargets.get(taskId);
						if (isClosing == null) {
							runOnlyOnceTemporaryTargets.put(
								taskId, execute = Boolean.TRUE);
						}
					}
				}
			}
		}
		return execute;
	}
	
	public QueuedTasksExecutor waitForTasksEnding() {
		return waitForTasksEnding(Thread.currentThread().getPriority());
	}
	
	public QueuedTasksExecutor waitForTasksEnding(int priority) {
		executor.setPriority(priority);
		tasksQueue.stream().forEach(executable -> executable.changePriority(priority));
		while (!tasksQueue.isEmpty()) {
			synchronized(mutexManager.getMutex("executingFinishedWaiter")) {
				if (!tasksQueue.isEmpty()) {
					try {
						mutexManager.getMutex("executingFinishedWaiter").wait();
					} catch (InterruptedException exc) {
						logWarn("Exception occurred", exc);
					}
				}
			}
		}
		executor.setPriority(this.defaultPriority);
		return this;
	}
	
	public QueuedTasksExecutor changePriority(int priority) {
		this.defaultPriority = priority;
		executor.setPriority(priority);
		tasksQueue.stream().forEach(executable -> executable.changePriority(priority));
		return this;
	}
	
	public QueuedTasksExecutor suspend() {
		return suspend(true);
	}
	
	public QueuedTasksExecutor suspend(boolean immediately) {
		return suspend0(immediately, Thread.currentThread().getPriority());
	}
	
	public QueuedTasksExecutor suspend(boolean immediately, int priority) {
		return suspend0(immediately, priority);
	}
	
	QueuedTasksExecutor suspend0(boolean immediately, int priority) {
		executor.setPriority(priority);
		if (immediately) {
			supended = Boolean.TRUE;
			if (!currentTask.hasFinished) {
				synchronized (mutexManager.getMutex("suspensionCaller")) {
					if (!currentTask.hasFinished) {
						try {
							mutexManager.getMutex("suspensionCaller").wait();
						} catch (InterruptedException exc) {
							logWarn("Exception occurred", exc);
						}
					}
				}
			}
		} else {
			Task suspensionTask = createTask((Runnable)() -> supended = Boolean.TRUE, priority);
			suspensionTask.addToQueue();
			Iterator<TaskAbst<?>> taskIterator = tasksQueue.iterator();
			while (taskIterator.hasNext()) {
				TaskAbst<?> task = taskIterator.next();
				if (task != suspensionTask) {
					task.changePriority(priority);
				} else {
					break;
				}
			}
		}
		return this;
	}

	public QueuedTasksExecutor resume() {
		synchronized(mutexManager.getMutex("resumeCaller")) {
			try {
				supended = Boolean.FALSE;
				mutexManager.getMutex("resumeCaller").notifyAll();
			} catch (Throwable exc) {
				logWarn("Exception occurred", exc);
			}
		}	
		return this;
	}
	
	public boolean isSuspended() {
		return supended;
	}
	
	public void shutDown(boolean waitForTasksTermination) {
		Collection<TaskAbst<?>> executables = this.tasksQueue;
		Thread executor = this.executor;
		if (waitForTasksTermination) {
			createTaskWithCurrentThreadPriority(() -> {
				this.terminated = Boolean.TRUE;
				logInfo("Unexecuted tasks {}", executables.size());
				executables.clear();
				runOnlyOnceTemporaryTargets.clear();
			}).addToQueue();
		} else {
			suspend();
			this.terminated = Boolean.TRUE;
			logInfo("Unexecuted tasks {}", executables.size());
			executables.clear();
			runOnlyOnceTemporaryTargets.clear();
			resume();
			try {
				synchronized(mutexManager.getMutex("executableCollectionFiller")) {
					mutexManager.getMutex("executableCollectionFiller").notifyAll();
				}
			} catch (Throwable exc) {
				logWarn("Exception occurred", exc);
			}	
		}
		try {
			executor.join();
			closeResources();			
		} catch (InterruptedException exc) {
			logError("Exception occurred", exc);
		}
	}
	
	@Override
	public void close() {
		shutDown(true);
	}
	
	void closeResources() {
		try {
			executor.interrupt();
		} catch (Throwable e) {
			logWarn("Exception occurred while interrupting thread {} of {}", executor, this);
		}
		executor = null;
		runOnlyOnceTemporaryTargets = null;
		mutexManager.clear();
		mutexManager = null;
		tasksQueue = null;
		currentTask = null;
		initializer = null;
		terminated = null;
		supended = null;
		logInfo("All resources of '{}' have been closed", name);
		name = null;		
	}
	
	public static abstract class TaskAbst<E> implements ManagedLogger {
		E executable;
		boolean hasFinished;
		int priority;
		Thread queuedTasksExecutorThread;
		Throwable exc;
		
		TaskAbst(int priority, Thread queuedTasksExecutorThread) {
			this.queuedTasksExecutorThread = queuedTasksExecutorThread;
			this.priority = priority;
		}
		
		public boolean hasFinished() {
			return hasFinished;
		}
		
		void join0(boolean ignoreThread) {
			if (!hasFinished() && ((ignoreThread) ||
				(!ignoreThread && Thread.currentThread() != queuedTasksExecutorThread && queuedTasksExecutorThread != null))
			) {
				synchronized (this) {
					if (!hasFinished() && ((ignoreThread) ||
						(!ignoreThread && Thread.currentThread() != queuedTasksExecutorThread && queuedTasksExecutorThread != null))) {
						try {
							wait();
						} catch (InterruptedException exc) {
							throw Throwables.toRuntimeException(exc);
						}
					}
				}
			}
		}
		
		void execute() {
			try {
				execute0();						
			} catch (Throwable exc) {
				this.exc = exc;
				logError("Exception occurred while executing " + this, exc);
			}			
			markHasFinished();
			executable = null;
			queuedTasksExecutorThread = null;
			synchronized (this) {
				notifyAll();
			}
		}
		
		abstract void execute0();
		
		void markHasFinished() {
			hasFinished = true;
		}
		
		public <T extends TaskAbst<E>> T changePriority(int priority) {
			this.priority = priority;
			return (T)this;
		}
		
		public int getPriority() {
			return priority;
		}
		
		public Throwable getException() {
			return exc;
		}
		
		public boolean endedWithErrors() {
			return exc != null;
		}
		
	}
	
	public static abstract class Task extends TaskAbst<Runnable> {
		
		Task(Runnable executable, int priority, Thread queuedTasksExecutorThread) {
			super(priority, queuedTasksExecutorThread);
			this.executable = executable;
		}

		@Override
		void execute0() {
			this.executable.run();			
		}
		
		public void join(boolean ignoreThread) {
			join0(ignoreThread);
		}
		
		public void join() {
			join0(false);
		}
		
		public abstract Task addToQueue();

	}
	
	public static abstract class ProducerTask<T> extends TaskAbst<Supplier<T>> {
		private T result;
		
		ProducerTask(Supplier<T> executable, int priority, Thread queuedTasksExecutorThread) {
			super(priority, queuedTasksExecutorThread);
			this.executable = executable;
		}		
		
		@Override
		void execute0() {
			result = executable.get();			
		}
		
		public T join() {
			return join(false);
		}
		
		public T join(boolean ignoreThread) {
			join0(ignoreThread);
			return result;
		}
		
		public T get() {
			return result;
		}
		
		public abstract ProducerTask<T> addToQueue();
	}
}