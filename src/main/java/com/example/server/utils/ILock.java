package com.example.server.utils;

public interface ILock {

    boolean tryLock(long timeoutSec);

    void unlock();
}
