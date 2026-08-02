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
package io.datavines.server.repository.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.datavines.core.enums.Status;
import io.datavines.server.api.dto.bo.user.*;
import io.datavines.server.api.dto.vo.UserBaseInfo;
import io.datavines.server.api.dto.vo.UserLoginResult;
import io.datavines.server.repository.entity.User;
import io.datavines.server.repository.entity.UserWorkSpace;
import io.datavines.server.repository.mapper.UserMapper;
import io.datavines.server.repository.service.UserService;
import io.datavines.core.exception.DataVinesServerException;
import io.datavines.server.repository.service.UserWorkSpaceService;
import io.datavines.server.utils.ContextHolder;
import jodd.util.BCrypt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service("userService")
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    @Autowired
    private UserWorkSpaceService userWorkSpaceService;

    @Override
    public User getByUsername(String username) {
        return baseMapper.selectOne(new QueryWrapper<User>().lambda().eq(User::getUsername,username));
    }

    @Override
    public UserLoginResult login(UserLogin userLogin) throws DataVinesServerException {
        String username = userLogin.getUsername();
        String password = userLogin.getPassword();

        User user = getByUsername(username);
        if (user != null) {

            boolean checkPassword = BCrypt.checkpw(password, user.getPassword());
            if (checkPassword) {
                UserLoginResult result = new UserLoginResult();
                BeanUtils.copyProperties(user, result);
                return result;
            } else {
                log.error("Username({}) password is wrong", username);
                throw new DataVinesServerException(Status.USERNAME_OR_PASSWORD_ERROR);
            }
        }

        throw new DataVinesServerException(Status.USERNAME_OR_PASSWORD_ERROR);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserBaseInfo createUserInWorkspace(UserCreate userCreate) throws DataVinesServerException {
        Long operatorId = ContextHolder.getUserId();
        UserWorkSpace operatorWs = userWorkSpaceService.getOne(new QueryWrapper<UserWorkSpace>().lambda()
                .eq(UserWorkSpace::getUserId, operatorId)
                .eq(UserWorkSpace::getWorkspaceId, userCreate.getWorkspaceId()));
        if (operatorWs == null || operatorWs.getRoleId() == null || operatorWs.getRoleId() != 1L) {
            throw new DataVinesServerException(Status.USER_HAS_NO_AUTHORIZE_TO_REMOVE);
        }

        String username = userCreate.getUsername();
        if (isUserExist(username)) {
            throw new DataVinesServerException(Status.USERNAME_HAS_BEEN_REGISTERED_ERROR, username);
        }

        User user = new User();
        user.setUsername(username);
        user.setEmail(userCreate.getEmail());
        user.setPhone(userCreate.getPhone());
        user.setPassword(BCrypt.hashpw(userCreate.getPassword(), BCrypt.gensalt()));
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        if (baseMapper.insert(user) <= 0) {
            throw new DataVinesServerException(Status.REGISTER_USER_ERROR, username);
        }

        UserWorkSpace exist = userWorkSpaceService.getOne(new QueryWrapper<UserWorkSpace>().lambda()
                .eq(UserWorkSpace::getUserId, user.getId())
                .eq(UserWorkSpace::getWorkspaceId, userCreate.getWorkspaceId()));
        if (exist != null) {
            throw new DataVinesServerException(Status.USER_IS_IN_WORKSPACE_ERROR);
        }

        UserWorkSpace userWorkSpace = new UserWorkSpace();
        userWorkSpace.setUserId(user.getId());
        userWorkSpace.setWorkspaceId(userCreate.getWorkspaceId());
        Long roleId = userCreate.getRoleId() == null ? 2L : userCreate.getRoleId();
        if (roleId != 1L && roleId != 2L) {
            throw new DataVinesServerException(Status.USER_ROLE_INVALID);
        }
        userWorkSpace.setRoleId(roleId);
        userWorkSpace.setCreateBy(operatorId);
        userWorkSpace.setCreateTime(LocalDateTime.now());
        userWorkSpace.setUpdateBy(operatorId);
        userWorkSpace.setUpdateTime(LocalDateTime.now());
        userWorkSpaceService.save(userWorkSpace);

        UserBaseInfo userBaseInfo = new UserBaseInfo();
        BeanUtils.copyProperties(user, userBaseInfo);
        return userBaseInfo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserBaseInfo register(UserRegister userRegister) throws DataVinesServerException {
        throw new DataVinesServerException(Status.REGISTER_CLOSED_ERROR);
    }

    @Override
    public Boolean updateUserInfo(UserUpdate userUpdate) {
        return null;
    }

    @Override
    public Boolean resetPassword(UserResetPassword userResetPassword) {
        User user = getById(userResetPassword.getId());
        if (user == null) {
            log.info("User({}) not exist", userResetPassword.getId());
            throw new DataVinesServerException(Status.USER_IS_NOT_EXIST_ERROR);
        }

        boolean checkPassword = BCrypt.checkpw(userResetPassword.getOldPassword(), user.getPassword());
        if (checkPassword) {
            if (!userResetPassword.getNewPassword().equals(userResetPassword.getNewPasswordConfirm())) {
                throw new DataVinesServerException(Status.NEW_PASSWORD_CONFIRM_IS_INCORRECT_ERROR);
            }

            user.setPassword(BCrypt.hashpw(userResetPassword.getNewPassword(), BCrypt.gensalt()));
            user.setUpdateTime(LocalDateTime.now());
            updateById(user);
            return true;
        } else {
            throw new DataVinesServerException(Status.OLD_PASSWORD_IS_INCORRECT_ERROR);
        }
    }

    private boolean isUserExist(String username) {
        User user = getByUsername(username);
        return user != null;
    }

}
