/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.qpid.jms.test.testpeer;

import java.util.List;

import org.apache.qpid.proton.amqp.Binary;
import org.apache.qpid.proton.amqp.DescribedType;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.UnsignedLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractFrameFieldAndPayloadMatchingHandler extends AbstractFieldAndDescriptorMatcher implements FrameHandler
{
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractFrameFieldAndPayloadMatchingHandler.class);

    public static int ANY_CHANNEL = -1;

    private final FrameType _frameType;

    /** The expected channel number, or {@link #ANY_CHANNEL} if we don't care */
    private int _expectedChannel;
    private int _actualChannel;

    private AmqpPeerRunnable _onSuccessAction;
    private volatile boolean _isComplete;

    protected AbstractFrameFieldAndPayloadMatchingHandler(FrameType frameType,
                                                int channel,
                                                UnsignedLong numericDescriptor,
                                                Symbol symbolicDescriptor,
                                                AmqpPeerRunnable onSuccessAction)
    {
        super(numericDescriptor, symbolicDescriptor);
        _frameType = frameType;
        _expectedChannel = channel;
        _onSuccessAction = onSuccessAction;
    }

    /**
     * Handle the supplied frame and its payload, e.g. by checking that it matches what we expect
     * @throws RuntimeException or a subclass thereof if the frame does not match what we expect
     */
    protected void verifyFrame(List<Object> described, Binary payload)
    {
        verifyFields(described);
        verifyPayload(payload);
    }

    protected abstract void verifyPayload(Binary payload);

    @SuppressWarnings("unchecked")
    @Override
    public void frame(int type, int ch, DescribedType dt, Binary payload, TestAmqpPeer peer)
    {
        if(type == _frameType.ordinal()
           && (_expectedChannel == ANY_CHANNEL || _expectedChannel == ch)
           && descriptorMatches(dt.getDescriptor())
           && (dt.getDescribed() instanceof List))
        {
            _actualChannel = ch;
            verifyFrame((List<Object>)dt.getDescribed(),payload);
            succeeded();
        }
        else
        {
            throw new IllegalArgumentException(String.format(
                    "Frame was not as expected. Expected: " +
                    "type=%s, channel=%s, descriptor=%s/%s but got: " +
                    "type=%s, channel=%s, descriptor=%s",
                    _frameType.ordinal(), expectedChannelString(), getSymbolicDescriptor(), getNumericDescriptor(),
                    type, ch, dt.getDescriptor()));
        }
    }

    private String expectedChannelString()
    {
        return _expectedChannel == ANY_CHANNEL ? "<any>" : String.valueOf(_expectedChannel);
    }

    private void succeeded()
    {
        if(_onSuccessAction != null)
        {
            _onSuccessAction.run();
        }
        else
        {
            LOGGER.debug("No onSuccess action, doing nothing.");
        }

        _isComplete = true;
    }

    public AmqpPeerRunnable getOnSuccessAction()
    {
        return _onSuccessAction;
    }

    public AbstractFrameFieldAndPayloadMatchingHandler onSuccess(AmqpPeerRunnable onSuccessAction)
    {
        _onSuccessAction = onSuccessAction;
        return this;
    }

    public AbstractFrameFieldAndPayloadMatchingHandler onChannel(int channel)
    {
        _expectedChannel = channel;
        return this;
    }

    public int getActualChannel()
    {
        return _actualChannel;
    }

    @Override
    public boolean isComplete()
    {
        return _isComplete;
    }

    @Override
    public String toString()
    {
        return "AbstractFrameFieldAndPayloadMatchingHandler [_symbolicDescriptor=" + getSymbolicDescriptor()
                + ", _expectedChannel=" + expectedChannelString()
                + "]";
    }
}