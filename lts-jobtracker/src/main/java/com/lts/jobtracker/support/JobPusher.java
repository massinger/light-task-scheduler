package com.lts.jobtracker.support;

import com.lts.core.commons.utils.Holder;
import com.lts.core.json.JSON;
import com.lts.core.constant.Constants;
import com.lts.core.exception.RemotingSendException;
import com.lts.core.exception.RequestTimeoutException;
import com.lts.core.factory.NamedThreadFactory;
import com.lts.core.logger.Logger;
import com.lts.core.logger.LoggerFactory;
import com.lts.core.protocol.JobProtos;
import com.lts.core.protocol.command.JobPullRequest;
import com.lts.core.protocol.command.JobPushRequest;
import com.lts.core.remoting.RemotingServerDelegate;
import com.lts.core.support.JobDomainConverter;
import com.lts.core.support.SystemClock;
import com.lts.jobtracker.domain.JobTrackerAppContext;
import com.lts.jobtracker.domain.TaskTrackerNode;
import com.lts.jobtracker.monitor.JobTrackerMStatReporter;
import com.lts.jobtracker.sender.JobPushResult;
import com.lts.jobtracker.sender.JobSender;
import com.lts.queue.domain.JobPo;
import com.lts.store.jdbc.exception.DupEntryException;
import com.lts.remoting.AsyncCallback;
import com.lts.remoting.ResponseFuture;
import com.lts.remoting.protocol.RemotingCommand;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * @author Robert HG (254963746@qq.com) on 8/18/14.
 *         任务分发管理
 */
public class JobPusher {

    private final Logger LOGGER = LoggerFactory.getLogger(JobPusher.class);
    private JobTrackerAppContext appContext;
    private final ExecutorService executorService;
    private JobTrackerMStatReporter stat;
    private RemotingServerDelegate remotingServer;

    public JobPusher(JobTrackerAppContext appContext) {
        this.appContext = appContext;
        this.executorService = Executors.newFixedThreadPool(Constants.AVAILABLE_PROCESSOR * 5,
                new NamedThreadFactory(JobPusher.class.getSimpleName(), true));
        this.stat = (JobTrackerMStatReporter) appContext.getMStatReporter();
        this.remotingServer = appContext.getRemotingServer();
    }

    public void concurrentPush(final JobPullRequest request) {

        this.executorService.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    push(request);
                } catch (Exception e) {
                    LOGGER.error("Job push failed!", e);
                }
            }
        });
    }

    private void push(final JobPullRequest request) {

        String nodeGroup = request.getNodeGroup();
        String identity = request.getIdentity();
        // 更新TaskTracker的可用线程数
        appContext.getTaskTrackerManager().updateTaskTrackerAvailableThreads(nodeGroup,
                identity, request.getAvailableThreads(), request.getTimestamp());

        TaskTrackerNode taskTrackerNode = appContext.getTaskTrackerManager().
                getTaskTrackerNode(nodeGroup, identity);

        if (taskTrackerNode == null) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("taskTrackerNodeGroup:{}, taskTrackerIdentity:{} , didn't have node.", nodeGroup, identity);
            }
            return;
        }

        int availableThreads = taskTrackerNode.getAvailableThread().get();
        if (availableThreads == 0) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("taskTrackerNodeGroup:{}, taskTrackerIdentity:{} , availableThreads:0", nodeGroup, identity);
            }
        }
        while (availableThreads > 0) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("taskTrackerNodeGroup:{}, taskTrackerIdentity:{} , availableThreads:{}",
                        nodeGroup, identity, availableThreads);
            }
            // 推送任务
            JobPushResult result = send(remotingServer, taskTrackerNode);
            switch (result) {
                case SUCCESS:
                    availableThreads = taskTrackerNode.getAvailableThread().decrementAndGet();
                    stat.incPushJobNum();
                    break;
                case FAILED:
                    // 还是要继续发送
                    break;
                case NO_JOB:
                    // 没有任务了
                    return;
                case SENT_ERROR:
                    // TaskTracker链接失败
                    return;
            }
        }
    }

    /**
     * 是否推送成功
     */
    private JobPushResult send(final RemotingServerDelegate remotingServer, final TaskTrackerNode taskTrackerNode) {

        final String nodeGroup = taskTrackerNode.getNodeGroup();
        final String identity = taskTrackerNode.getIdentity();

        JobSender.SendResult sendResult = appContext.getJobSender().send(nodeGroup, identity, new JobSender.SendInvoker() {
            @Override
            public JobSender.SendResult invoke(final JobPo jobPo) {

                // 发送给TaskTracker执行
                JobPushRequest body = appContext.getCommandBodyWrapper().wrapper(new JobPushRequest());
                body.setJobWrapper(JobDomainConverter.convert(jobPo));
                RemotingCommand commandRequest = RemotingCommand.createRequestCommand(JobProtos.RequestCode.PUSH_JOB.code(), body);

                // 是否分发推送任务成功
                final Holder<Boolean> pushSuccess = new Holder<Boolean>(false);

                final CountDownLatch latch = new CountDownLatch(1);
                try {
                    remotingServer.invokeAsync(taskTrackerNode.getChannel().getChannel(), commandRequest, new AsyncCallback() {
                        @Override
                        public void operationComplete(ResponseFuture responseFuture) {
                            try {
                                RemotingCommand responseCommand = responseFuture.getResponseCommand();
                                if (responseCommand == null) {
                                    LOGGER.warn("Job push failed! response command is null!");
                                    return;
                                }
                                if (responseCommand.getCode() == JobProtos.ResponseCode.JOB_PUSH_SUCCESS.code()) {
                                    if (LOGGER.isDebugEnabled()) {
                                        LOGGER.debug("Job push success! nodeGroup=" + nodeGroup + ", identity=" + identity + ", job=" + jobPo);
                                    }
                                    pushSuccess.set(true);
                                }
                            } finally {
                                latch.countDown();
                            }
                        }
                    });

                } catch (RemotingSendException e) {
                    LOGGER.error("Remoting send error, jobPo={}", jobPo, e);
                    return new JobSender.SendResult(false, JobPushResult.SENT_ERROR);
                }

                try {
                    latch.await(Constants.LATCH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    throw new RequestTimeoutException(e);
                }

                if (!pushSuccess.get()) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Job push failed! nodeGroup=" + nodeGroup + ", identity=" + identity + ", job=" + jobPo);
                    }
                    // 队列切回来
                    boolean needResume = true;
                    try {
                        jobPo.setIsRunning(true);
                        jobPo.setGmtModified(SystemClock.now());
                        appContext.getExecutableJobQueue().add(jobPo);
                    } catch (DupEntryException e) {
                        LOGGER.warn("ExecutableJobQueue already exist:" + JSON.toJSONString(jobPo));
                        needResume = false;
                    }
                    appContext.getExecutingJobQueue().remove(jobPo.getJobId());
                    if (needResume) {
                        appContext.getExecutableJobQueue().resume(jobPo);
                    }
                    return new JobSender.SendResult(false, JobPushResult.SENT_ERROR);
                }

                return new JobSender.SendResult(true, JobPushResult.SUCCESS);
            }
        });

        return (JobPushResult) sendResult.getReturnValue();
    }
}
