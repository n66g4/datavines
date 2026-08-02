/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.datavines.server.api.controller;

import io.datavines.common.exception.DataVinesException;
import io.datavines.core.constant.DataVinesConstants;
import io.datavines.core.entity.ResultMap;
import io.datavines.core.enums.Status;
import io.datavines.core.exception.DataVinesServerException;
import io.datavines.core.utils.TokenManager;
import io.datavines.server.api.annotation.AuthIgnore;
import io.datavines.server.api.dto.bo.user.UserLogin;
import io.datavines.server.api.dto.bo.user.UserRegister;
import io.datavines.server.api.dto.vo.UserLoginResult;
import io.datavines.server.repository.service.UserService;
import io.datavines.server.utils.LoginAttemptGuard;
import io.datavines.server.utils.VerificationUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

@Api(value = "login", tags = "login")
@RestController
@Validated
@RequestMapping(value = DataVinesConstants.BASE_API_PATH)
public class LoginController {

    @Autowired
    private UserService userService;

    @Autowired
    private TokenManager tokenManager;

    @Autowired
    private LoginAttemptGuard loginAttemptGuard;

    @AuthIgnore
    @ApiOperation(value = "login")
    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object login(@Valid @RequestBody UserLogin userLogin, HttpServletRequest request) throws DataVinesException {
        String ip = clientIp(request);
        loginAttemptGuard.assertNotLocked(userLogin.getUsername(), ip);

        if (loginAttemptGuard.needCaptcha(userLogin.getUsername(), ip)) {
            try {
                if (StringUtils.isBlank(userLogin.getVerificationCode())
                        || StringUtils.isBlank(userLogin.getVerificationCodeJwt())) {
                    throw new DataVinesServerException(Status.LOGIN_CAPTCHA_REQUIRED);
                }
                VerificationUtil.validVerificationCode(userLogin.getVerificationCode(), userLogin.getVerificationCodeJwt());
            } catch (DataVinesServerException e) {
                loginAttemptGuard.onFailure(userLogin.getUsername(), ip);
                if (Boolean.TRUE.equals(loginAttemptGuard.status(userLogin.getUsername(), ip).get("locked"))) {
                    throw new DataVinesServerException(Status.LOGIN_ACCOUNT_LOCKED);
                }
                throw e;
            }
        }

        try {
            UserLoginResult result = userService.login(userLogin);
            loginAttemptGuard.onSuccess(userLogin.getUsername(), ip);
            return new ResultMap(tokenManager)
                    .successWithToken(userLogin.getUsername(), userLogin.getPassword())
                    .payload(result);
        } catch (DataVinesServerException e) {
            if (e.getStatus() == Status.USERNAME_OR_PASSWORD_ERROR) {
                loginAttemptGuard.onFailure(userLogin.getUsername(), ip);
                if (Boolean.TRUE.equals(loginAttemptGuard.status(userLogin.getUsername(), ip).get("locked"))) {
                    throw new DataVinesServerException(Status.LOGIN_ACCOUNT_LOCKED);
                }
            }
            throw e;
        }
    }

    @AuthIgnore
    @ApiOperation(value = "login attempt status")
    @GetMapping(value = "/login/attemptStatus")
    public Object attemptStatus(@RequestParam(value = "username", required = false) String username,
                                HttpServletRequest request) {
        return new ResultMap().success().payload(loginAttemptGuard.status(username, clientIp(request)));
    }

    @AuthIgnore
    @ApiOperation(value = "register")
    @PostMapping(value = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object register(@RequestBody(required = false) UserRegister userRegister) throws DataVinesException {
        // Public registration is closed; admins create users via /workspace/createUser.
        throw new DataVinesServerException(Status.REGISTER_CLOSED_ERROR);
    }

    @AuthIgnore
    @ApiOperation(value = "refreshVerificationCode")
    @GetMapping(value = "/refreshVerificationCode")
    public Object refreshVerificationCode() {
        return new ResultMap().success().payload(VerificationUtil.createVerificationCodeAndImage());
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.isNotBlank(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.isNotBlank(realIp)) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
