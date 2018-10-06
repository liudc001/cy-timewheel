/*
 * Copyright (C) 2018 Baidu, Inc. All Rights Reserved.
 */
package cn.cy.timewheel.core;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;

/**
 * 时间轮核心逻辑
 * <p>
 * 这里并未使用分层时间轮，而是复用同一个时间轮
 * 在每个槽上有轮数来标识，现在是走到的第几圈
 * 支持的最小单位是秒
 */
public class TimeWheel {

	private TickTimer tickTimer;

	private static Logger logger = LoggerContext.getContext().getLogger(TimeWheel.class.getName());

	// 一圈的槽数
	private final int slotNum;

	private static final int DEFAULT_SLOT_NUM = 20;

	// 一个槽所代表的时间,单位是ms
	private final int milliSecondsPerSlot;

	private static final int DEFAULT_TIME_PER_SLOT = 100;

	// 现在走到的指针
	private volatile int point;

	// 轮数, 每走过一圈, 轮数自增
	private volatile long round;

	private Executor executor;

	private ArrayList<Slot<ScheduledEvent>> slotList;

	public static TimeWheel build() {
		return new TimeWheel(DEFAULT_SLOT_NUM, DEFAULT_TIME_PER_SLOT);
	}

	// 任务开始时间
	private long startTime;

	// 任务开始计数
	private long startCnt;

	private TimeWheel(int slotNum, int milliSecondsPerSlot) {
		this.slotNum = slotNum;
		this.milliSecondsPerSlot = milliSecondsPerSlot;
		tickTimer = new BlockingQueueTimer(milliSecondsPerSlot);
		startCnt = 0;

		slotList = new ArrayList<>();
		// 默认2s一圈
		for (int i = 0; i < slotNum; i++) {
			slotList.add(Slot.buildEmptySlot(i, this));
		}

		executor = Executors.newFixedThreadPool(20);
	}

	public void start() {

		if (startCnt > 0) {
			throw new IllegalArgumentException("the timer can only started once !");
		}

		startCnt++;
		startTime = System.currentTimeMillis();

		new Thread(() -> {
			while (true) {
				// 计时一次
				// logger.debug("once");
				tickTimer.once();

				synchronized(this) {
					Slot nowSlot = slotList.get(point);
					final long tarRound = round;
					final int nowPoint = point;

					executor.execute(() -> {
						logger.debug("pollEvent {} slot, {} tarRound", nowPoint, tarRound);
						nowSlot.pollEvent(tarRound);
					});

					point++;
					if (point >= slotNum) {
						point %= slotNum;
						// long都溢出了, 这程序得跑到人类灭亡把...
						round++;
					}
				}

			}
		}).start();
	}

	/**
	 * 在millisLater毫秒之后进行任务
	 *
	 * @param event
	 * @param millisLater
	 */
	public void addEvent(ScheduledEvent event, long millisLater) {

		// 注释中的方法只能用于不锁时钟线程的情况下
		//            long nowTime = System.currentTimeMillis();
		//            long targetMilliTime = nowTime + millisLater - startTime;
		//
		//            long tarRound = targetMilliTime / (slotNum * milliSecondsPerSlot);
		//            long tarSlotIndex = (targetMilliTime / milliSecondsPerSlot) % slotNum;
		//
		//            Slot<ScheduledEvent> tarSlot = slotList.get((int) tarSlotIndex);
		//            logger.debug("event has been added into {} slot, {} tarRound", tarSlotIndex, tarRound);
		//            tarSlot.addEvent(tarRound, event);

		int nextIndex = -1;
		long tarRound = -1;

		synchronized(this) {
			long deltaSlotIndex = millisLater / milliSecondsPerSlot;

			if (deltaSlotIndex == 0) {
				deltaSlotIndex++;
			}

			nextIndex = (point + (int) deltaSlotIndex);
			tarRound = round;

			if (nextIndex >= slotNum) {
				nextIndex -= slotNum;
				tarRound++;
			}
		}
		Slot<ScheduledEvent> tarSlot = slotList.get(nextIndex);

		logger.debug("nowIndex {}, nextIndex {}", point, nextIndex);

		tarSlot.addEvent(tarRound, event);
	}

	/**
	 * 槽位的类定义
	 * <p>
	 * 不使用分层策略, 而是复用这一层.
	 * 每个槽会维护一个 Map<round, List<Event>> 的数据结构
	 */
	public static class Slot<Event extends ScheduledEvent> {

		// 现在这一槽位所处于的轮数
		private volatile int nowRound;

		// 这个slot所在的下标
		private final int index;

		// Map<round, List<Event>>
		private volatile ConcurrentHashMap<Long, ConcurrentLinkedQueue<Event>> eventMap;

		private final TimeWheel timeWheel;

		private Slot(int nowRound,
		             ConcurrentHashMap<Long, ConcurrentLinkedQueue<Event>> eventMap,
		             int index, TimeWheel timeWheel) {
			this.nowRound = nowRound;
			this.eventMap = eventMap;
			this.index = index;
			this.timeWheel = timeWheel;
		}

		@SuppressWarnings("unchecked")
		public static Slot buildEmptySlot(int index, TimeWheel timeWheel) {
			return new Slot(0, new ConcurrentHashMap<>(), index, timeWheel);
		}

		public void addEvent(long tarRound, Event event) {

			synchronized(timeWheel) {
				//                if (tarRound < nowRound) {
				//                    throw new IllegalArgumentException("you can't add the event into the past");
				//                }

				// 更新任务
				ConcurrentLinkedQueue<Event> queue = eventMap.getOrDefault(tarRound, null);
				if (queue == null) {
					queue = new ConcurrentLinkedQueue<>();
					eventMap.put(tarRound, queue);
				}

				queue.offer(event);

				event.startTimingCallback();
			}
		}

		// 循环指定round的任务, 进行回调
		public void pollEvent(long tarRound) {
			ConcurrentLinkedQueue<Event> queue = eventMap.getOrDefault(tarRound, null);

			if (queue == null) {
				logger.debug("There is no events in the slot, tarRound {}", tarRound);
				return;
			}

			while (!queue.isEmpty()) {
				// 执行event的回调方法
				Event event = queue.poll();
				event.timeoutCallback();
			}

			// remove the element, help gc
			eventMap.remove(tarRound);
		}
	}
}