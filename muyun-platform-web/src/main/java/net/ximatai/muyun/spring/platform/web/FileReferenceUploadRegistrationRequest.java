package net.ximatai.muyun.spring.platform.web;

/** Identity and business destination are resolved from the server's ticket registration. */
public record FileReferenceUploadRegistrationRequest(String ticketId, String fileId) { }
