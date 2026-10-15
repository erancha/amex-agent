package com.erancha.amexagent.statements;

import com.erancha.amexagent.card.api.StatementRejectedException;
import com.erancha.amexagent.card.api.StatementStore;
import com.erancha.amexagent.card.api.StoredStatement;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

/**
 * The HTTP edge of the statements page: lists the statement files the agent reads, loads one
 * more from a multipart upload and deletes one by file name. Every refusal is a problem document
 * whose detail names the cause. Multipart parsing is lazy (application.yml), so an oversized
 * upload fails inside this controller and gets the same treatment.
 */
@RestController
@RequestMapping(StatementsEndpoint.API)
public class StatementsEndpoint {
  /** The statements page's path on the agent's base URL, served from static/statements.html. */
  public static final String PAGE = "/statements.html";
  public static final String API = "/api/statements";
  private static final Logger log = LoggerFactory.getLogger(StatementsEndpoint.class);
  private final StatementStore store;
  private final DataSize maxFileSize;

  public StatementsEndpoint(StatementStore store,
      @Value("${spring.servlet.multipart.max-file-size}") DataSize maxFileSize) {
    this.store = store;
    this.maxFileSize = maxFileSize;
  }

  @GetMapping
  public List<StoredStatement> list() {
    return store.list();
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public StoredStatement add(@RequestParam("file") MultipartFile file) throws IOException {
    return store.add(file.getOriginalFilename(), file.getBytes());
  }

  @DeleteMapping("/{fileName}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void remove(@PathVariable("fileName") String fileName) {
    store.remove(fileName);
  }

  @ExceptionHandler(StatementRejectedException.class)
  ProblemDetail rejected(StatementRejectedException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ProblemDetail tooLarge(MaxUploadSizeExceededException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE,
        "The file is larger than the " + maxFileSize.toMegabytes() + " MB upload limit");
  }

  @ExceptionHandler(UncheckedIOException.class)
  ProblemDetail folderFailure(UncheckedIOException e) {
    log.error("Statements folder failure", e);
    return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
  }
}
