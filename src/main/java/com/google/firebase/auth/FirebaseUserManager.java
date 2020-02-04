/*
 * Copyright 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.firebase.auth;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpContent;
import com.google.api.client.http.HttpMethods;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponseInterceptor;
import com.google.api.client.http.json.JsonHttpContent;
import com.google.api.client.json.GenericJson;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.util.Key;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.firebase.ErrorCode;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseException;
import com.google.firebase.ImplFirebaseTrampolines;
import com.google.firebase.IncomingHttpResponse;
import com.google.firebase.auth.UserRecord.CreateRequest;
import com.google.firebase.auth.UserRecord.UpdateRequest;
import com.google.firebase.auth.internal.AuthServiceErrorResponse;
import com.google.firebase.auth.internal.DownloadAccountResponse;
import com.google.firebase.auth.internal.GetAccountInfoResponse;
import com.google.firebase.auth.internal.UploadAccountResponse;
import com.google.firebase.internal.AbstractHttpErrorHandler;
import com.google.firebase.internal.ApiClientUtils;
import com.google.firebase.internal.ErrorHandlingHttpClient;
import com.google.firebase.internal.HttpRequestInfo;
import com.google.firebase.internal.NonNull;
import com.google.firebase.internal.Nullable;
import com.google.firebase.internal.SdkUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * FirebaseUserManager provides methods for interacting with the Google Identity Toolkit via its
 * REST API. This class does not hold any mutable state, and is thread safe.
 *
 * @see <a href="https://developers.google.com/identity/toolkit/web/reference/relyingparty">
 *   Google Identity Toolkit</a>
 */
class FirebaseUserManager {

  static final int MAX_LIST_USERS_RESULTS = 1000;
  static final int MAX_IMPORT_USERS = 1000;

  static final List<String> RESERVED_CLAIMS = ImmutableList.of(
      "amr", "at_hash", "aud", "auth_time", "azp", "cnf", "c_hash", "exp", "iat",
      "iss", "jti", "nbf", "nonce", "sub", "firebase");

  private static final String ID_TOOLKIT_URL =
      "https://identitytoolkit.googleapis.com/v1/projects/%s";
  private static final String CLIENT_VERSION_HEADER = "X-Client-Version";
  private static final String CLIENT_VERSION = "Java/Admin/" + SdkUtils.getVersion();

  private final String baseUrl;
  private final JsonFactory jsonFactory;
  private final ErrorHandlingHttpClient<FirebaseAuthException> httpClient;

  private HttpResponseInterceptor interceptor;

  /**
   * Creates a new FirebaseUserManager instance.
   *
   * @param app A non-null {@link FirebaseApp}.
   */
  FirebaseUserManager(@NonNull FirebaseApp app) {
    this(app, null);
  }

  FirebaseUserManager(@NonNull FirebaseApp app, @Nullable HttpRequestFactory requestFactory) {
    checkNotNull(app, "FirebaseApp must not be null");
    String projectId = ImplFirebaseTrampolines.getProjectId(app);
    checkArgument(!Strings.isNullOrEmpty(projectId),
        "Project ID is required to access the auth service. Use a service account credential or "
            + "set the project ID explicitly via FirebaseOptions. Alternatively you can also "
            + "set the project ID via the GOOGLE_CLOUD_PROJECT environment variable.");
    this.baseUrl = String.format(ID_TOOLKIT_URL, projectId);
    this.jsonFactory = app.getOptions().getJsonFactory();
    if (requestFactory == null) {
      requestFactory = ApiClientUtils.newAuthorizedRequestFactory(app);
    }

    AuthErrorHandler authErrorHandler = new AuthErrorHandler(jsonFactory);
    this.httpClient = new ErrorHandlingHttpClient<>(requestFactory, jsonFactory, authErrorHandler);
  }

  @VisibleForTesting
  void setInterceptor(HttpResponseInterceptor interceptor) {
    this.interceptor = interceptor;
  }

  UserRecord getUserById(String uid) throws FirebaseAuthException {
    final Map<String, Object> payload = ImmutableMap.<String, Object>of(
        "localId", ImmutableList.of(uid));
    return lookupUserAccount(payload, "user ID: " + uid);
  }

  UserRecord getUserByEmail(String email) throws FirebaseAuthException {
    final Map<String, Object> payload = ImmutableMap.<String, Object>of(
        "email", ImmutableList.of(email));
    return lookupUserAccount(payload, "email: " + email);
  }

  UserRecord getUserByPhoneNumber(String phoneNumber) throws FirebaseAuthException {
    final Map<String, Object> payload = ImmutableMap.<String, Object>of(
        "phoneNumber", ImmutableList.of(phoneNumber));
    return lookupUserAccount(payload, "phone number: " + phoneNumber);
  }

  String createUser(CreateRequest request) throws FirebaseAuthException {
    GenericJson response = post(
        "/accounts", request.getProperties(), GenericJson.class);
    return (String) response.get("localId");
  }

  void updateUser(UpdateRequest request, JsonFactory jsonFactory) throws FirebaseAuthException {
    post("/accounts:update", request.getProperties(jsonFactory), GenericJson.class);
  }

  void deleteUser(String uid) throws FirebaseAuthException {
    final Map<String, Object> payload = ImmutableMap.<String, Object>of("localId", uid);
    post("/accounts:delete", payload, GenericJson.class);
  }

  DownloadAccountResponse listUsers(int maxResults, String pageToken) throws FirebaseAuthException {
    ImmutableMap.Builder<String, Object> builder = ImmutableMap.<String, Object>builder()
        .put("maxResults", maxResults);
    if (pageToken != null) {
      checkArgument(!pageToken.equals(ListUsersPage.END_OF_LIST), "invalid end of list page token");
      builder.put("nextPageToken", pageToken);
    }

    GenericUrl url = new GenericUrl(baseUrl + "/accounts:batchGet");
    url.putAll(builder.build());
    return sendRequest(
        HttpMethods.GET, url.toString(), null, DownloadAccountResponse.class);
  }

  UserImportResult importUsers(UserImportRequest request) throws FirebaseAuthException {
    checkNotNull(request);
    UploadAccountResponse response = post(
        "/accounts:batchCreate", request, UploadAccountResponse.class);
    return new UserImportResult(request.getUsersCount(), response);
  }

  String createSessionCookie(String idToken,
      SessionCookieOptions options) throws FirebaseAuthException {
    final Map<String, Object> payload = ImmutableMap.<String, Object>of(
        "idToken", idToken, "validDuration", options.getExpiresInSeconds());
    GenericJson response = post(":createSessionCookie", payload, GenericJson.class);
    return (String) response.get("sessionCookie");
  }

  String getEmailActionLink(EmailLinkType type, String email,
      @Nullable ActionCodeSettings settings) throws FirebaseAuthException {
    ImmutableMap.Builder<String, Object> payload = ImmutableMap.<String, Object>builder()
            .put("requestType", type.name())
            .put("email", email)
            .put("returnOobLink", true);
    if (settings != null) {
      payload.putAll(settings.getProperties());
    }

    GenericJson response = post("/accounts:sendOobCode", payload.build(), GenericJson.class);
    return (String) response.get("oobLink");
  }

  private UserRecord lookupUserAccount(
      Map<String, Object> payload, String identifier) throws FirebaseAuthException {
    IncomingHttpResponse response = sendRequest(
        HttpMethods.POST, baseUrl + "/accounts:lookup", payload);
    GetAccountInfoResponse parsed = httpClient.parse(response, GetAccountInfoResponse.class);
    if (parsed.getUsers() == null || parsed.getUsers().isEmpty()) {
      throw new FirebaseAuthException(ErrorCode.NOT_FOUND,
          "No user record found for the provided " + identifier,
          null,
          response,
          AuthErrorCode.USER_NOT_FOUND);
    }

    return new UserRecord(parsed.getUsers().get(0), jsonFactory);
  }

  private <T> T post(String path, Object content, Class<T> clazz) throws FirebaseAuthException {
    checkArgument(!Strings.isNullOrEmpty(path), "path must not be null or empty");
    checkNotNull(content, "content must not be null for POST requests");
    return sendRequest(HttpMethods.POST, baseUrl + path, content, clazz);
  }

  private <T> T sendRequest(
      String method, String url, @Nullable Object content, Class<T> clazz)
      throws FirebaseAuthException {

    IncomingHttpResponse response = sendRequest(method, url, content);
    return httpClient.parse(response, clazz);
  }

  private IncomingHttpResponse sendRequest(
      String method, String url, @Nullable Object content) throws FirebaseAuthException {

    HttpContent httpContent = content != null ? new JsonHttpContent(jsonFactory, content) : null;
    HttpRequestInfo requestInfo = HttpRequestInfo.buildRequest(method, url, httpContent)
        .addHeader(CLIENT_VERSION_HEADER, CLIENT_VERSION)
        .setResponseInterceptor(interceptor);
    return httpClient.send(requestInfo);
  }

  static class UserImportRequest extends GenericJson {

    @Key("users")
    private final List<Map<String, Object>> users;

    UserImportRequest(List<ImportUserRecord> users, UserImportOptions options,
        JsonFactory jsonFactory) {
      checkArgument(users != null && !users.isEmpty(), "users must not be null or empty");
      checkArgument(users.size() <= FirebaseUserManager.MAX_IMPORT_USERS,
          "users list must not contain more than %s items", FirebaseUserManager.MAX_IMPORT_USERS);

      boolean hasPassword = false;
      ImmutableList.Builder<Map<String, Object>> usersBuilder = ImmutableList.builder();
      for (ImportUserRecord user : users) {
        if (user.hasPassword()) {
          hasPassword = true;
        }
        usersBuilder.add(user.getProperties(jsonFactory));
      }
      this.users = usersBuilder.build();

      if (hasPassword) {
        checkArgument(options != null && options.getHash() != null,
            "UserImportHash option is required when at least one user has a password. Provide "
                + "a UserImportHash via UserImportOptions.withHash().");
        this.putAll(options.getProperties());
      }
    }

    int getUsersCount() {
      return users.size();
    }
  }

  enum EmailLinkType {
    VERIFY_EMAIL,
    EMAIL_SIGNIN,
    PASSWORD_RESET,
  }

  private static class AuthErrorHandler extends AbstractHttpErrorHandler<FirebaseAuthException> {

    private final JsonFactory jsonFactory;

    AuthErrorHandler(JsonFactory jsonFactory) {
      this.jsonFactory = jsonFactory;
    }

    @Override
    protected FirebaseAuthException createException(FirebaseException base) {
      String response = getResponse(base);
      AuthServiceErrorResponse parsed = safeParse(response);
      String message = parsed.getErrorMessage();
      if (Strings.isNullOrEmpty(message)) {
        message = base.getMessage();
      }

      return new FirebaseAuthException(
          base.getErrorCodeNew(),
          message,
          base.getCause(),
          base.getHttpResponse(),
          parsed.getAuthErrorCode());
    }

    private String getResponse(FirebaseException base) {
      if (base.getHttpResponse() == null) {
        return null;
      }

      return base.getHttpResponse().getContent();
    }

    private AuthServiceErrorResponse safeParse(String response) {
      if (!Strings.isNullOrEmpty(response)) {
        try {
          return jsonFactory.createJsonParser(response)
              .parseAndClose(AuthServiceErrorResponse.class);
        } catch (IOException ignore) {
          // Ignore any error that may occur while parsing the error response. The server
          // may have responded with a non-json payload.
        }
      }

      return new AuthServiceErrorResponse();
    }
  }
}
