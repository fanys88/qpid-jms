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
package org.apache.qpid.jms.integration;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Arrays;

import javax.jms.BytesMessage;
import javax.jms.Connection;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;

import org.apache.qpid.jms.provider.amqp.message.AmqpMessageSupport;
import org.apache.qpid.jms.test.QpidJmsTestCase;
import org.apache.qpid.jms.test.testpeer.TestAmqpPeer;
import org.apache.qpid.jms.test.testpeer.describedtypes.sections.AmqpValueDescribedType;
import org.apache.qpid.jms.test.testpeer.describedtypes.sections.DataDescribedType;
import org.apache.qpid.jms.test.testpeer.describedtypes.sections.MessageAnnotationsDescribedType;
import org.apache.qpid.jms.test.testpeer.describedtypes.sections.PropertiesDescribedType;
import org.apache.qpid.jms.test.testpeer.matchers.sections.MessageAnnotationsSectionMatcher;
import org.apache.qpid.jms.test.testpeer.matchers.sections.MessageHeaderSectionMatcher;
import org.apache.qpid.jms.test.testpeer.matchers.sections.MessagePropertiesSectionMatcher;
import org.apache.qpid.jms.test.testpeer.matchers.sections.TransferPayloadCompositeMatcher;
import org.apache.qpid.jms.test.testpeer.matchers.types.EncodedDataMatcher;
import org.apache.qpid.proton.amqp.Binary;
import org.apache.qpid.proton.amqp.DescribedType;
import org.apache.qpid.proton.amqp.Symbol;
import org.junit.Test;

public class BytesMessageIntegrationTest extends QpidJmsTestCase {
    private final IntegrationTestFixture testFixture = new IntegrationTestFixture();

    @Test(timeout = 5000)
    public void testSendBasicBytesMessageWithContent() throws Exception {
        try (TestAmqpPeer testPeer = new TestAmqpPeer();) {
            Connection connection = testFixture.establishConnecton(testPeer);
            testPeer.expectBegin(true);
            testPeer.expectSenderAttach();

            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue queue = session.createQueue("myQueue");
            MessageProducer producer = session.createProducer(queue);

            byte[] content = "myBytes".getBytes();

            MessageHeaderSectionMatcher headersMatcher = new MessageHeaderSectionMatcher(true).withDurable(equalTo(true));
            MessageAnnotationsSectionMatcher msgAnnotationsMatcher = new MessageAnnotationsSectionMatcher(true);
            msgAnnotationsMatcher.withEntry(Symbol.valueOf(AmqpMessageSupport.JMS_MSG_TYPE), equalTo(AmqpMessageSupport.JMS_BYTES_MESSAGE));
            MessagePropertiesSectionMatcher propertiesMatcher = new MessagePropertiesSectionMatcher(true);
            propertiesMatcher.withContentType(equalTo(Symbol.valueOf(AmqpMessageSupport.OCTET_STREAM_CONTENT_TYPE)));
            TransferPayloadCompositeMatcher messageMatcher = new TransferPayloadCompositeMatcher();
            messageMatcher.setHeadersMatcher(headersMatcher);
            messageMatcher.setMessageAnnotationsMatcher(msgAnnotationsMatcher);
            messageMatcher.setPropertiesMatcher(propertiesMatcher);
            messageMatcher.setMessageContentMatcher(new EncodedDataMatcher(new Binary(content)));

            testPeer.expectTransfer(messageMatcher);

            BytesMessage message = session.createBytesMessage();
            message.writeBytes(content);

            producer.send(message);
        }
    }

    @Test(timeout = 5000)
    public void testReceiveBasicBytesMessageWithContentUsingDataSection() throws Exception {
        try (TestAmqpPeer testPeer = new TestAmqpPeer();) {
            Connection connection = testFixture.establishConnecton(testPeer);
            connection.start();

            testPeer.expectBegin(true);

            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue queue = session.createQueue("myQueue");

            PropertiesDescribedType properties = new PropertiesDescribedType();
            properties.setContentType(Symbol.valueOf(AmqpMessageSupport.OCTET_STREAM_CONTENT_TYPE));

            MessageAnnotationsDescribedType msgAnnotations = new MessageAnnotationsDescribedType();
            msgAnnotations.setSymbolKeyedAnnotation(AmqpMessageSupport.JMS_MSG_TYPE, AmqpMessageSupport.JMS_BYTES_MESSAGE);

            final byte[] expectedContent = "expectedContent".getBytes();
            DescribedType dataContent = new DataDescribedType(new Binary(expectedContent));

            testPeer.expectReceiverAttach();
            testPeer.expectLinkFlowRespondWithTransfer(null, msgAnnotations, properties, null, dataContent);
            testPeer.expectDispositionThatIsAcceptedAndSettled();

            MessageConsumer messageConsumer = session.createConsumer(queue);
            Message receivedMessage = messageConsumer.receive(1000);
            testPeer.waitForAllHandlersToComplete(3000);

            assertNotNull(receivedMessage);
            assertTrue(receivedMessage instanceof BytesMessage);
            BytesMessage bytesMessage = (BytesMessage) receivedMessage;
            assertEquals(expectedContent.length, bytesMessage.getBodyLength());
            byte[] recievedContent = new byte[expectedContent.length];
            int readBytes = bytesMessage.readBytes(recievedContent);
            assertEquals(recievedContent.length, readBytes);
            assertTrue(Arrays.equals(expectedContent, recievedContent));
        }
    }

    /**
     * Test that a message received from the test peer with a Data section and content type of
     * {@link AmqpMessageSupport#OCTET_STREAM_CONTENT_TYPE} is returned as a BytesMessage, verify it
     * gives the expected data values when read, and when reset and left mid-stream before being
     * resent that it results in the expected AMQP data body section and properties content type
     * being received by the test peer.
     */
    @Test(timeout = 5000)
    public void testReceiveBytesMessageAndResendAfterResetAndPartialRead() throws Exception {
        try (TestAmqpPeer testPeer = new TestAmqpPeer();) {
            Connection connection = testFixture.establishConnecton(testPeer);
            connection.start();

            testPeer.expectBegin(true);

            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue queue = session.createQueue("myQueue");

            // Prepare an AMQP message for the test peer to send, containing the content type and
            // a data body section populated with expected bytes for use as a JMS BytesMessage
            PropertiesDescribedType properties = new PropertiesDescribedType();
            Symbol contentType = Symbol.valueOf(AmqpMessageSupport.OCTET_STREAM_CONTENT_TYPE);
            properties.setContentType(contentType);

            MessageAnnotationsDescribedType msgAnnotations = new MessageAnnotationsDescribedType();
            msgAnnotations.setSymbolKeyedAnnotation(AmqpMessageSupport.JMS_MSG_TYPE, AmqpMessageSupport.JMS_BYTES_MESSAGE);

            boolean myBool = true;
            byte myByte = 4;
            byte[] myBytes = "myBytes".getBytes();
            char myChar = 'd';
            double myDouble = 1234567890123456789.1234;
            float myFloat = 1.1F;
            int myInt = Integer.MAX_VALUE;
            long myLong = Long.MAX_VALUE;
            short myShort = 25;
            String myUTF = "myString";

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            dos.writeBoolean(myBool);
            dos.writeByte(myByte);
            dos.write(myBytes);
            dos.writeChar(myChar);
            dos.writeDouble(myDouble);
            dos.writeFloat(myFloat);
            dos.writeInt(myInt);
            dos.writeLong(myLong);
            dos.writeShort(myShort);
            dos.writeUTF(myUTF);

            byte[] bytesPayload = baos.toByteArray();
            Binary binaryPayload = new Binary(bytesPayload);
            DescribedType dataSectionContent = new DataDescribedType(binaryPayload);

            // receive the message from the test peer
            testPeer.expectReceiverAttach();
            testPeer.expectLinkFlowRespondWithTransfer(null, msgAnnotations, properties, null, dataSectionContent);
            testPeer.expectDispositionThatIsAcceptedAndSettled();

            MessageConsumer messageConsumer = session.createConsumer(queue);
            Message receivedMessage = messageConsumer.receive(1000);
            testPeer.waitForAllHandlersToComplete(3000);

            // verify the content is as expected
            assertNotNull("Message was not received", receivedMessage);
            assertTrue("Message was not a BytesMessage", receivedMessage instanceof BytesMessage);
            BytesMessage receivedBytesMessage = (BytesMessage) receivedMessage;

            assertEquals("Unexpected boolean value", myBool, receivedBytesMessage.readBoolean());
            assertEquals("Unexpected byte value", myByte, receivedBytesMessage.readByte());
            byte[] readBytes = new byte[myBytes.length];
            assertEquals("Did not read the expected number of bytes", myBytes.length, receivedBytesMessage.readBytes(readBytes));
            assertTrue("Read bytes were not as expected: " + Arrays.toString(readBytes), Arrays.equals(myBytes, readBytes));
            assertEquals("Unexpected char value", myChar, receivedBytesMessage.readChar());
            assertEquals("Unexpected double value", myDouble, receivedBytesMessage.readDouble(), 0.0);
            assertEquals("Unexpected float value", myFloat, receivedBytesMessage.readFloat(), 0.0);
            assertEquals("Unexpected int value", myInt, receivedBytesMessage.readInt());
            assertEquals("Unexpected long value", myLong, receivedBytesMessage.readLong());
            assertEquals("Unexpected short value", myShort, receivedBytesMessage.readShort());
            assertEquals("Unexpected UTF value", myUTF, receivedBytesMessage.readUTF());

            // reset and read the first item, leaving message marker in the middle of its content
            receivedBytesMessage.reset();
            assertEquals("Unexpected boolean value after reset", myBool, receivedBytesMessage.readBoolean());

            // Send the received message back to the test peer and have it check the result is as expected
            testPeer.expectSenderAttach();
            MessageProducer producer = session.createProducer(queue);

            MessageHeaderSectionMatcher headersMatcher = new MessageHeaderSectionMatcher(true);
            MessageAnnotationsSectionMatcher msgAnnotationsMatcher = new MessageAnnotationsSectionMatcher(true);
            MessagePropertiesSectionMatcher propsMatcher = new MessagePropertiesSectionMatcher(true);
            propsMatcher.withContentType(equalTo(contentType));
            TransferPayloadCompositeMatcher messageMatcher = new TransferPayloadCompositeMatcher();
            messageMatcher.setHeadersMatcher(headersMatcher);
            messageMatcher.setMessageAnnotationsMatcher(msgAnnotationsMatcher);
            messageMatcher.setPropertiesMatcher(propsMatcher);
            messageMatcher.setMessageContentMatcher(new EncodedDataMatcher(binaryPayload));
            testPeer.expectTransfer(messageMatcher);

            producer.send(receivedBytesMessage);

            testPeer.waitForAllHandlersToComplete(3000);
        }
    }

    /**
     * Test that a message received from the test peer with an AmqpValue section containing
     * Binary and no content type is returned as a BytesMessage, verify it gives the
     * expected data values when read, and when sent to the test peer it results in an
     * AMQP message containing a data body section and content type of
     * {@link AmqpMessageSupport#OCTET_STREAM_CONTENT_TYPE}
     */
    @Test(timeout = 5000)
    public void testReceiveBytesMessageWithAmqpValueAndResendResultsInData() throws Exception {
        try (TestAmqpPeer testPeer = new TestAmqpPeer();) {
            Connection connection = testFixture.establishConnecton(testPeer);
            connection.start();

            testPeer.expectBegin(true);

            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue queue = session.createQueue("myQueue");

            // Prepare an AMQP message for the test peer to send, containing an amqp-value
            // body section populated with expected bytes for use as a JMS BytesMessage,
            // and do not set content type, or the message type annotation

            boolean myBool = true;
            byte myByte = 4;
            byte[] myBytes = "myBytes".getBytes();
            char myChar = 'd';
            double myDouble = 1234567890123456789.1234;
            float myFloat = 1.1F;
            int myInt = Integer.MAX_VALUE;
            long myLong = Long.MAX_VALUE;
            short myShort = 25;
            String myUTF = "myString";

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            dos.writeBoolean(myBool);
            dos.writeByte(myByte);
            dos.write(myBytes);
            dos.writeChar(myChar);
            dos.writeDouble(myDouble);
            dos.writeFloat(myFloat);
            dos.writeInt(myInt);
            dos.writeLong(myLong);
            dos.writeShort(myShort);
            dos.writeUTF(myUTF);

            byte[] bytesPayload = baos.toByteArray();
            Binary binaryPayload = new Binary(bytesPayload);

            DescribedType amqpValueSectionContent = new AmqpValueDescribedType(binaryPayload);

            // receive the message from the test peer
            testPeer.expectReceiverAttach();
            testPeer.expectLinkFlowRespondWithTransfer(null, null, null, null, amqpValueSectionContent);
            testPeer.expectDispositionThatIsAcceptedAndSettled();

            MessageConsumer messageConsumer = session.createConsumer(queue);
            Message receivedMessage = messageConsumer.receive(1000);
            testPeer.waitForAllHandlersToComplete(3000);

            // verify the content is as expected
            assertNotNull("Message was not received", receivedMessage);
            assertTrue("Message was not a BytesMessage", receivedMessage instanceof BytesMessage);
            BytesMessage receivedBytesMessage = (BytesMessage) receivedMessage;

            assertEquals("Unexpected boolean value", myBool, receivedBytesMessage.readBoolean());
            assertEquals("Unexpected byte value", myByte, receivedBytesMessage.readByte());
            byte[] readBytes = new byte[myBytes.length];
            assertEquals("Did not read the expected number of bytes", myBytes.length, receivedBytesMessage.readBytes(readBytes));
            assertTrue("Read bytes were not as expected: " + Arrays.toString(readBytes), Arrays.equals(myBytes, readBytes));
            assertEquals("Unexpected char value", myChar, receivedBytesMessage.readChar());
            assertEquals("Unexpected double value", myDouble, receivedBytesMessage.readDouble(), 0.0);
            assertEquals("Unexpected float value", myFloat, receivedBytesMessage.readFloat(), 0.0);
            assertEquals("Unexpected int value", myInt, receivedBytesMessage.readInt());
            assertEquals("Unexpected long value", myLong, receivedBytesMessage.readLong());
            assertEquals("Unexpected short value", myShort, receivedBytesMessage.readShort());
            assertEquals("Unexpected UTF value", myUTF, receivedBytesMessage.readUTF());

            // reset and read the first item, leaving message marker in the middle of its content
            receivedBytesMessage.reset();
            assertEquals("Unexpected boolean value after reset", myBool, receivedBytesMessage.readBoolean());

            // Send the received message back to the test peer and have it check the result is as expected
            testPeer.expectSenderAttach();
            MessageProducer producer = session.createProducer(queue);

            MessageHeaderSectionMatcher headersMatcher = new MessageHeaderSectionMatcher(true);
            MessageAnnotationsSectionMatcher msgAnnotationsMatcher = new MessageAnnotationsSectionMatcher(true);
            msgAnnotationsMatcher.withEntry(Symbol.valueOf(AmqpMessageSupport.JMS_MSG_TYPE), equalTo(AmqpMessageSupport.JMS_BYTES_MESSAGE));
            MessagePropertiesSectionMatcher propsMatcher = new MessagePropertiesSectionMatcher(true);
            propsMatcher.withContentType(equalTo(Symbol.valueOf(AmqpMessageSupport.OCTET_STREAM_CONTENT_TYPE)));
            TransferPayloadCompositeMatcher messageMatcher = new TransferPayloadCompositeMatcher();
            messageMatcher.setHeadersMatcher(headersMatcher);
            messageMatcher.setMessageAnnotationsMatcher(msgAnnotationsMatcher);
            messageMatcher.setPropertiesMatcher(propsMatcher);
            messageMatcher.setMessageContentMatcher(new EncodedDataMatcher(binaryPayload));
            testPeer.expectTransfer(messageMatcher);

            producer.send(receivedBytesMessage);

            testPeer.waitForAllHandlersToComplete(3000);
        }
    }
}
