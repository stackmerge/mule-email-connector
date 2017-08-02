/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.extension.email.internal.commands;

import static java.lang.Math.min;
import static java.util.Collections.emptyList;
import static javax.mail.Folder.READ_ONLY;
import static javax.mail.Folder.READ_WRITE;
import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.mule.extension.email.internal.util.EmailConnectorConstants.CONTENT_TYPE_HEADER;
import static org.mule.runtime.api.metadata.MediaType.ANY;
import static org.mule.runtime.core.api.message.DefaultMultiPartPayload.BODY_ATTRIBUTES;

import org.mule.extension.email.api.attributes.BaseEmailAttributes;
import org.mule.extension.email.api.exception.CannotFetchMetadataException;
import org.mule.extension.email.api.exception.EmailException;
import org.mule.extension.email.api.predicate.BaseEmailPredicateBuilder;
import org.mule.extension.email.internal.mailbox.MailboxAccessConfiguration;
import org.mule.extension.email.internal.mailbox.MailboxConnection;
import org.mule.extension.email.internal.util.EmailContentProcessor;
import org.mule.runtime.api.message.Message;
import org.mule.runtime.api.metadata.MediaType;
import org.mule.runtime.core.api.message.DefaultMultiPartPayload;
import org.mule.runtime.extension.api.runtime.operation.Result;
import org.mule.runtime.extension.api.runtime.streaming.PagingProvider;
import javax.mail.Folder;
import javax.mail.MessagingException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;


/**
 * {@link PagingProvider} implementation for list emails operation.
 *
 * @since 1.0
 */
public final class PagingProviderEmailDelegate<T extends BaseEmailAttributes>
    implements PagingProvider<MailboxConnection, Result<Object, T>> {

  private final MailboxAccessConfiguration configuration;
  private final int pageSize;
  private Folder folder;
  private final String folderName;
  private final BaseEmailPredicateBuilder matcherBuilder;
  private int bottom = 1;
  private int top;
  private final boolean deleteAfterRetrieve;
  private final Consumer<BaseEmailAttributes> deleteAfterReadCallback;

  // TODO: MULE-13097 - REMOVE WHEN SDK Injects the connection on PagingProvider#close() method
  private MailboxConnection connection;

  /**
   * @param configuration           The {@link MailboxAccessConfiguration} associated to this operation.
   * @param folderName              the name of the folder where the emails are stored.
   * @param matcherBuilder          a {@link Predicate} of {@link BaseEmailAttributes} used to filter the output list
   * @param pageSize                size of the block that would be retrieved from the email server. This page doesn't represent the page size to
   *                                be returned by the {@link PagingProvider} because emails must be tested against the {@link BaseEmailPredicateBuilder}
   *                                matcher after retrieval to see if they fulfill matcher's condition.
   * @param deleteAfterRetrieve     whether the emails should be deleted after retrieval
   * @param deleteAfterReadCallback callback for deleting each email
   */
  public PagingProviderEmailDelegate(MailboxAccessConfiguration configuration, String folderName,
                                     BaseEmailPredicateBuilder matcherBuilder,
                                     int pageSize,
                                     boolean deleteAfterRetrieve, Consumer<BaseEmailAttributes> deleteAfterReadCallback) {
    this.configuration = configuration;
    this.folderName = folderName;
    this.matcherBuilder = matcherBuilder;
    this.pageSize = pageSize;
    this.top = pageSize;
    this.deleteAfterRetrieve = deleteAfterRetrieve;
    this.deleteAfterReadCallback = deleteAfterReadCallback;
  }

  /**
   * Retrieves emails numbered from {@code bottom} up to {@code top} in the specified {@code folderName}.
   * <p>
   * A new {@link Result} is created for each fetched email from the folder, where the payload is the text body of the email and
   * the other metadata is carried by an {@link BaseEmailAttributes} instance.
   * <p>
   * For folder implementations (like IMAP) that support fetching without reading the content, if the content should NOT be read
   * ({@code shouldReadContent} = false) the SEEN flag is not going to be set. If {@code deleteAfterRead} flag is set to true, the
   * callback {@code deleteAfterReadCallback} is applied to each email.
   */
  private <T extends BaseEmailAttributes> List<Result<Object, T>> list(int startIndex, int endIndex) {
    Predicate<BaseEmailAttributes> matcher = matcherBuilder != null ? matcherBuilder.build() : e -> true;
    try {
      List<Result<Object, T>> retrievedEmails = new LinkedList<>();
      for (javax.mail.Message m : folder.getMessages(startIndex, endIndex)) {
        Object emailContent = EMPTY;
        T attributes = configuration.parseAttributesFromMessage(m, folder);
        if (matcher.test(attributes)) {
          if (configuration.isEagerlyFetchContent()) {
            emailContent = readContent(m);
            // Attributes are parsed again since they may change after the email has been read.
            attributes = configuration.parseAttributesFromMessage(m, folder);
          }
          Result<Object, T> result = Result.<Object, T>builder()
              .output(emailContent)
              .attributes(attributes)
              .mediaType(getMediaType(attributes))
              .build();

          retrievedEmails.add(result);
        }

        if (deleteAfterRetrieve) {
          deleteAfterReadCallback.accept(attributes);
        }
      }

      return retrievedEmails;
    } catch (CannotFetchMetadataException e) {
      throw e;
    } catch (MessagingException me) {
      throw new EmailException("Error while retrieving emails: " + me.getMessage(), me);
    }
  }

  @Override
  public List<Result<Object, T>> getPage(MailboxConnection connection) {
    this.connection = connection;
    try {
      folder = connection.getFolder(folderName, deleteAfterRetrieve ? READ_WRITE : READ_ONLY);
      int count = folder.getMessageCount();
      if (count != 0) {
        top = min(top, count);
        while (bottom <= top) {
          List<Result<Object, T>> emails = list(bottom, top);
          bottom += pageSize;
          top = min(top + pageSize, count);
          if (!emails.isEmpty()) {
            return emails;
          }
        }
      }
    } catch (MessagingException e) {
      throw new EmailException("Error while retrieving emails: ", e);
    }
    return emptyList();
  }

  /**
   * @param connection The connection to be used to do the query.
   * @return {@link Optional#empty()} because a priori there is no way for knowing how many emails are going to be tested
   * {@code true} against the {@link BaseEmailPredicateBuilder} matcher.
   */
  @Override
  public Optional<Integer> getTotalResults(MailboxConnection connection) {
    return Optional.empty();
  }

  @Override
  public void close() throws IOException {
    connection.closeFolder(true);
  }

  @Override
  public boolean useStickyConnections() {
    return true;
  }

  private MediaType getMediaType(BaseEmailAttributes attributes) {
    String contentType = attributes.getHeaders().get(CONTENT_TYPE_HEADER);
    return contentType == null ? ANY : MediaType.parse(contentType);
  }

  private Object readContent(javax.mail.Message m) {
    Object emailContent;
    EmailContentProcessor processor = EmailContentProcessor.getInstance(m);
    String body = processor.getBody();
    List<Message> parts = new ArrayList<>();
    List<Message> attachments = processor.getAttachments();

    if (!attachments.isEmpty()) {
      parts.add(Message.builder().value(body).attributesValue(BODY_ATTRIBUTES).build());
      parts.addAll(attachments);
      emailContent = new DefaultMultiPartPayload(parts);
    } else {
      emailContent = body;
    }
    return emailContent;
  }
}
