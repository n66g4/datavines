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

import io.datavines.core.constant.DataVinesConstants;
import io.datavines.core.aop.RefreshToken;
import io.datavines.server.api.dto.bo.user.UserCreate;
import io.datavines.server.api.dto.bo.workspace.RemoveUserOutWorkspace;
import io.datavines.server.api.dto.bo.workspace.UpdateUserWorkspaceRole;
import io.datavines.server.api.dto.bo.workspace.WorkSpaceCreate;
import io.datavines.server.api.dto.bo.workspace.WorkSpaceUpdate;
import io.datavines.server.repository.service.UserService;
import io.datavines.server.repository.service.WorkSpaceService;
import io.datavines.core.exception.DataVinesServerException;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@Api(value = "workspace", tags = "workspace", produces = MediaType.APPLICATION_JSON_VALUE)
@RestController
@RequestMapping(value = DataVinesConstants.BASE_API_PATH + "/workspace", produces = MediaType.APPLICATION_JSON_VALUE)
@RefreshToken
public class WorkSpaceController {

    @Autowired
    private WorkSpaceService workSpaceService;

    @Autowired
    private UserService userService;

    @ApiOperation(value = "create workspace")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object createWorkSpace(@Valid @RequestBody WorkSpaceCreate workSpaceCreate) throws DataVinesServerException {
        return workSpaceService.insert(workSpaceCreate);
    }

    @ApiOperation(value = "update workspace")
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object updateWorkSpace(@Valid @RequestBody WorkSpaceUpdate workSpaceUpdate) throws DataVinesServerException {
        return workSpaceService.update(workSpaceUpdate)>0;
    }

    @ApiOperation(value = "delete workspace")
    @DeleteMapping(value = "/{id}")
    public Object deleteWorkSpace(@PathVariable Long id)  {
        return workSpaceService.deleteById(id);
    }

    @ApiOperation(value = "list workspace by user id")
    @GetMapping(value = "list")
    public Object listByUserId()  {
        return workSpaceService.listByUserId();
    }

    @ApiOperation(value = "create user into workspace")
    @PostMapping(value = "/createUser", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object createUser(@Valid @RequestBody UserCreate userCreate) throws DataVinesServerException {
        return userService.createUserInWorkspace(userCreate);
    }

    @ApiOperation(value = "user removed workspace")
    @DeleteMapping(value = "/removeUser",consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object removeUser(@Valid @RequestBody RemoveUserOutWorkspace removeUserOutWorkspace)  {
        return workSpaceService.removeUser(removeUserOutWorkspace);
    }

    @ApiOperation(value = "update user role in workspace")
    @PutMapping(value = "/updateUserRole", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object updateUserRole(@Valid @RequestBody UpdateUserWorkspaceRole updateUserWorkspaceRole) {
        return workSpaceService.updateUserRole(updateUserWorkspaceRole);
    }

    @ApiOperation(value = "list user by workspace id")
    @GetMapping(value = "/userPage")
    public Object listUserByWorkspaceId(@RequestParam("workspaceId") Long workspaceId,
                                        @RequestParam("pageNumber") Integer pageNumber,
                                        @RequestParam("pageSize") Integer pageSize)  {
        return workSpaceService.listUserByWorkspaceId(workspaceId,pageNumber,pageSize);
    }

}
