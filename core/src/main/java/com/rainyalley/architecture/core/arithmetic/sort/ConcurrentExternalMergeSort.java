package com.rainyalley.architecture.core.arithmetic.sort;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ConcurrentExternalMergeSort{

    private static final int maxTask = 32;

    /**
     * 0-4个线程活动
     * 阻塞队列容量100
     * 最大空闲时间10秒
     */
    private final static ThreadPoolExecutor es = new ThreadPoolExecutor(0, 32, 10L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(maxTask));

    public ConcurrentExternalMergeSort() {
        es.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public <T extends Comparable<T>> void sort(ExternalStore<T> arr){


        // 一个子数组的长度
        // 从 1开始分割，与递归不同的是，递归由数组长度一分为二最后到1，
        // 而非递归则是从1开始扩大二倍直到数组长度
        long size = 1;

        while (size < arr.size()) {

            long taskNum = arr.size() / (size * 2);
            //表示处理最后一对数组时，存在右数组
            if(arr.size() % (size * 2) > size){
                taskNum++;
            }

            CountDownLatch taskLatch = new CountDownLatch(Long.valueOf(taskNum).intValue());

            // 完全二叉树一层内的遍历
            // left + size 即为 right的起始位置 ----> left + size <= arr.size() - 1 表示存在右数组 ----> 若不存在右数组，无需处理，因为左数组在上一级中已经有序
            for (long left = 0; left + size <= arr.size() - 1; left += size * 2) {
                //中间地址，即右数组的起始地址
                long right = left + size - 1;
                //右数组的尾部
                long end = left + size * 2 - 1;

                // 防止超出数组长度
                if (end > arr.size() - 1){
                    end = arr.size() - 1;
                }

                //合并
                es.submit(new mergeTask<>(arr, left, right, end, taskLatch));
            }

            try {
                taskLatch.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }

            // 下一层
            size *= 2;
        }
    }

    private static class mergeTask<T extends Comparable<T>> implements Runnable {

        private CountDownLatch latch;
        private ExternalStore<T> arr;
        private long left;
        private long right;
        private long end;

        public mergeTask(ExternalStore<T> arr, long left, long right, long end, CountDownLatch latch) {
            this.arr = arr;
            this.left = left;
            this.right = right;
            this.end = end;
            this.latch = latch;
        }

        @Override
        public void run() {
            merge(arr, left, right, end);
            latch.countDown();
        }

        /**
         * 合并并排序
         * @param arr 待排序数组
         * @param left 左数组起点
         * @param right 右数组起点
         * @param end 右数组终点
         */
        protected  <T extends Comparable<T>> void merge(ExternalStore<T> arr, long left, long right, long end) {
            ExternalStore<T> temp = arr.create(arr.name() + "_" + left + "_" + right + "_" + end, end - left + 1);

            try {
                //左边数组元素的位置
                long i = left;
                //右边数组元素的位置
                long j = right + 1;
                //temp数组元素的位置
                long k = 0;

                // 注意： 此处并没有全部放入temp中，当一边达到mid或right时就是退出循环
                while (i <= right && j <= end) {
                    //如果左边元素更小，就放入temp，位置+1
                    if (arr.get(i).compareTo(arr.get(j)) < 0){
                        temp.set(k++, arr.get(i++));
                    }
                    //如果右边元素更小，就放入temp，位置+1
                    else{
                        temp.set(k++, arr.get(j++));
                    }
                }

                // 如果左边或右边有剩余，则继续放入，只可能一边有剩余
                while (i <= right){
                    temp.set(k++, arr.get(i++));
                }

                while (j <= end){
                    temp.set(k++, arr.get(j++));
                }


                // 排好序的临时数组重新放入原数组
                for (int m = 0; m < temp.size(); m++) {
                    arr.set(m + left, temp.get(m));
                }
            } finally {
                temp.close();
            }


        }
    }


    private static class LinkedOfferBlockingQueue<E> extends LinkedBlockingQueue<E>{
        private static final long serialVersionUID = -8914170589029264025L;

        public LinkedOfferBlockingQueue(int maxSize) {
            super(maxSize);
        }

        @Override
        public boolean offer(E e) {
            // turn offer() and add() into a blocking calls (unless interrupted)
            try {
                put(e);
                return true;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

}
