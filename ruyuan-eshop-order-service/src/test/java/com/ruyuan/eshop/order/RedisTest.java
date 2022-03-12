package com.ruyuan.eshop.order;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

@SpringBootTest(classes = OrderApplication.class)
@RunWith(SpringJUnit4ClassRunner.class)
public class RedisTest {

    @Autowired
    private RedissonClient redissonClient;

    @Test
    public void test() {
        RLock lock = redissonClient.getLock("lock");
        lock.lock();
        System.out.println("加锁成功！");
        lock.unlock();
        System.out.println("解锁成功！");
    }
}
