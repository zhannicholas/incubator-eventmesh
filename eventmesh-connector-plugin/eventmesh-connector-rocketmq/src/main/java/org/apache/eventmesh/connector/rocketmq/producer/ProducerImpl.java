/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.eventmesh.connector.rocketmq.producer;

import org.apache.eventmesh.api.RRCallback;
import org.apache.eventmesh.connector.rocketmq.utils.OMSUtil;

import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.RequestCallback;
import org.apache.rocketmq.common.message.MessageClientIDSetter;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.remoting.exception.RemotingException;

import java.util.Properties;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openmessaging.api.Message;
import io.openmessaging.api.MessageBuilder;
import io.openmessaging.api.OnExceptionContext;
import io.openmessaging.api.Producer;
import io.openmessaging.api.SendCallback;
import io.openmessaging.api.SendResult;
import io.openmessaging.api.exception.OMSRuntimeException;

public class ProducerImpl extends AbstractOMSProducer implements Producer {

    public static final int eventMeshServerAsyncAccumulationThreshold = 1000;

    private final Logger logger = LoggerFactory.getLogger(ProducerImpl.class);

    public ProducerImpl(final Properties properties) {
        super(properties);
    }

    public Properties attributes() {
        return properties;
    }

    public void setExtFields() {
        super.getRocketmqProducer().setRetryTimesWhenSendFailed(0);
        super.getRocketmqProducer().setRetryTimesWhenSendAsyncFailed(0);
        super.getRocketmqProducer().setPollNameServerInterval(60000);

        super.getRocketmqProducer().getDefaultMQProducerImpl().getmQClientFactory()
            .getNettyClientConfig()
            .setClientAsyncSemaphoreValue(eventMeshServerAsyncAccumulationThreshold);
        super.getRocketmqProducer().setCompressMsgBodyOverHowmuch(10);
    }

    @Override
    public SendResult send(Message message) {
        this.checkProducerServiceState(rocketmqProducer.getDefaultMQProducerImpl());
        org.apache.rocketmq.common.message.Message msgRMQ = OMSUtil.msgConvert(message);

        try {
            org.apache.rocketmq.client.producer.SendResult sendResultRmq =
                this.rocketmqProducer.send(msgRMQ);
            message.setMsgID(sendResultRmq.getMsgId());
            SendResult sendResult = new SendResult();
            sendResult.setTopic(sendResultRmq.getMessageQueue().getTopic());
            sendResult.setMessageId(sendResultRmq.getMsgId());
            return sendResult;
        } catch (Exception e) {
            log.error(String.format("Send message Exception, %s", message), e);
            throw this.checkProducerException(message.getTopic(), message.getMsgID(), e);
        }
    }

    @Override
    public void sendOneway(Message message) {
        this.checkProducerServiceState(this.rocketmqProducer.getDefaultMQProducerImpl());
        org.apache.rocketmq.common.message.Message msgRMQ = OMSUtil.msgConvert(message);

        try {
            this.rocketmqProducer.sendOneway(msgRMQ);
            message.setMsgID(MessageClientIDSetter.getUniqID(msgRMQ));
        } catch (Exception e) {
            log.error(String.format("Send message oneway Exception, %s", message), e);
            throw this.checkProducerException(message.getTopic(), message.getMsgID(), e);
        }
    }

    @Override
    public void sendAsync(Message message, SendCallback sendCallback) {
        this.checkProducerServiceState(this.rocketmqProducer.getDefaultMQProducerImpl());
        org.apache.rocketmq.common.message.Message msgRMQ = OMSUtil.msgConvert(message);

        try {
            this.rocketmqProducer.send(msgRMQ, this.sendCallbackConvert(message, sendCallback));
            message.setMsgID(MessageClientIDSetter.getUniqID(msgRMQ));
        } catch (Exception e) {
            log.error(String.format("Send message async Exception, %s", message), e);
            throw this.checkProducerException(message.getTopic(), message.getMsgID(), e);
        }
    }

    public void request(Message message, RRCallback rrCallback, long timeout)
        throws InterruptedException, RemotingException, MQClientException, MQBrokerException {

        this.checkProducerServiceState(this.rocketmqProducer.getDefaultMQProducerImpl());
        org.apache.rocketmq.common.message.Message msgRMQ = OMSUtil.msgConvert(message);
        rocketmqProducer.request(msgRMQ, rrCallbackConvert(message, rrCallback), timeout);
    }

    private RequestCallback rrCallbackConvert(final Message message, final RRCallback rrCallback) {
        return new RequestCallback() {
            @Override
            public void onSuccess(org.apache.rocketmq.common.message.Message message) {
                Message openMessage = OMSUtil.msgConvert((MessageExt) message);
                rrCallback.onSuccess(openMessage);
            }

            @Override
            public void onException(Throwable e) {
                String topic = message.getTopic();
                String msgId = message.getMsgID();
                OMSRuntimeException onsEx =
                    ProducerImpl.this.checkProducerException(topic, msgId, e);
                OnExceptionContext context = new OnExceptionContext();
                context.setTopic(topic);
                context.setMessageId(msgId);
                context.setException(onsEx);
                rrCallback.onException(e);

            }
        };
    }

    private org.apache.rocketmq.client.producer.SendCallback sendCallbackConvert(
        final Message message, final SendCallback sendCallback) {
        org.apache.rocketmq.client.producer.SendCallback rmqSendCallback =
            new org.apache.rocketmq.client.producer.SendCallback() {
                @Override
                public void onSuccess(org.apache.rocketmq.client.producer.SendResult sendResult) {
                    sendCallback.onSuccess(OMSUtil.sendResultConvert(sendResult));
                }

                @Override
                public void onException(Throwable e) {
                    String topic = message.getTopic();
                    String msgId = message.getMsgID();
                    OMSRuntimeException onsEx =
                        ProducerImpl.this.checkProducerException(topic, msgId, e);
                    OnExceptionContext context = new OnExceptionContext();
                    context.setTopic(topic);
                    context.setMessageId(msgId);
                    context.setException(onsEx);
                    sendCallback.onException(context);
                }
            };
        return rmqSendCallback;
    }

    @Override
    public void setCallbackExecutor(ExecutorService callbackExecutor) {
//        this.rocketmqProducer.setCallbackExecutor(callbackExecutor);
    }

    @Override
    public void updateCredential(Properties credentialProperties) {

    }

    @Override
    public <T> MessageBuilder<T> messageBuilder() {
        return null;
    }
}
