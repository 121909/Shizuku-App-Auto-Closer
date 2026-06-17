package com.example.lockguard.remote;

interface IStopController {
    boolean forceStop(String packageName) = 1;
    void destroy() = 16777114;
}

