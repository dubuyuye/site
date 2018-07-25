package com.rainyalley.architecture.filter.limit;

import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

import static com.rainyalley.architecture.filter.limit.RejectReason.*;


public abstract class AbstractLimitFilter extends OncePerRequestFilter {

    private Console console;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String target = determineTarget(request);
        String caller = determineCaller(request);

        console.access(caller, target);

        boolean hasAuth = console.hasAuth(caller, target);
        if(!hasAuth){
            reject(NO_AUTH, request, response);
        }

        boolean notFrequency = this.notFrequency(caller, target);
        if(!notFrequency){
            reject(TOO_FREQUENCY, request, response);
        }

        boolean acquired = console.acquireConcurrency(caller, target);
        //申请并发量
        if(!acquired){
            reject(NO_CONCURRENCY, request, response);
            return;
        }

        try{
            filterChain.doFilter(request, response);
        } finally {
            console.releaseConcurrency(caller, target);
        }
    }

    private boolean notFrequency(String caller, String target){
        TargetLimit targetLimit = console.getTargetLimit(target);
        CallerLimit callerLimit = console.getCallerLimit(caller);
        if(targetLimit.getMinInterval() > 0){
            TargetRuntime rt = console.getTargetRuntime(target);
            return System.currentTimeMillis() - rt.getLastAccessTime() >= targetLimit.getMinInterval();
        }

        if(callerLimit.getMinInterval() > 0){
            CallerRuntime rt = console.getCallerRuntime(caller);
            long lastAccessTime = 0;
            if(!rt.getAccessInfoList().isEmpty()){
                lastAccessTime = rt.getAccessInfoList().get(0).getAccessTime();
            }
            return System.currentTimeMillis() - lastAccessTime >= targetLimit.getMinInterval();
        }

        return true;
    }

    public Console getConsole() {
        return console;
    }

    public void setConsole(Console console) {
        this.console = console;
    }

    /**
     * 获取 caller 值
     * @param request request
     * @return caller
     */
    protected abstract String determineCaller(HttpServletRequest request);

    /**
     * 获取 target 值
     * @param request request
     * @return target
     */
    protected abstract String determineTarget(HttpServletRequest request);

    /**
     * 拒绝请求的逻辑
     * @param reason 拒绝理由
     * @param request request
     * @param response response
     */
    protected abstract void reject(RejectReason reason, HttpServletRequest request, HttpServletResponse response);
}
