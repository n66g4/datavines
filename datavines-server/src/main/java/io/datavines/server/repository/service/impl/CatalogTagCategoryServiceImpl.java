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
import io.datavines.core.exception.DataVinesServerException;
import io.datavines.server.api.dto.bo.catalog.tag.TagCategoryCreate;
import io.datavines.server.api.dto.bo.catalog.tag.TagCategoryUpdate;
import io.datavines.server.repository.entity.catalog.CatalogEntityTagRel;
import io.datavines.server.repository.entity.catalog.CatalogTag;
import io.datavines.server.repository.entity.catalog.CatalogTagCategory;
import io.datavines.server.repository.mapper.CatalogTagCategoryMapper;
import io.datavines.server.repository.service.CatalogEntityTagRelService;
import io.datavines.server.repository.service.CatalogTagCategoryService;
import io.datavines.server.repository.service.CatalogTagService;
import io.datavines.server.utils.ContextHolder;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service("catalogTagCategoryService")
public class CatalogTagCategoryServiceImpl extends ServiceImpl<CatalogTagCategoryMapper, CatalogTagCategory> implements CatalogTagCategoryService {

    @Autowired
    private CatalogTagService tagService;

    @Autowired
    private CatalogEntityTagRelService catalogEntityTagRelService;

    @Override
    public long create(TagCategoryCreate categoryCreate) {
        if (isExistInWorkspace(categoryCreate.getWorkspaceId(), categoryCreate.getName(), null)) {
            throw new DataVinesServerException(Status.CATALOG_TAG_CATEGORY_EXIST_ERROR, categoryCreate.getName());
        }

        CatalogTagCategory catalogTag = new CatalogTagCategory();
        BeanUtils.copyProperties(categoryCreate, catalogTag);

        catalogTag.setUuid(UUID.randomUUID().toString());
        catalogTag.setCreateTime(LocalDateTime.now());
        catalogTag.setUpdateTime(LocalDateTime.now());
        catalogTag.setCreateBy(ContextHolder.getUserId());
        catalogTag.setUpdateBy(ContextHolder.getUserId());
        baseMapper.insert(catalogTag);
        return catalogTag.getId();
    }

    @Override
    public boolean update(TagCategoryUpdate categoryUpdate) {
        CatalogTagCategory existing = getOne(new QueryWrapper<CatalogTagCategory>().lambda()
                .eq(CatalogTagCategory::getUuid, categoryUpdate.getUuid()));
        if (existing == null) {
            throw new DataVinesServerException(Status.CATALOG_TAG_CATEGORY_NOT_EXIST_ERROR, categoryUpdate.getUuid());
        }
        if (isExistInWorkspace(existing.getWorkspaceId(), categoryUpdate.getName(), categoryUpdate.getUuid())) {
            throw new DataVinesServerException(Status.CATALOG_TAG_CATEGORY_EXIST_ERROR, categoryUpdate.getName());
        }
        existing.setName(categoryUpdate.getName());
        existing.setUpdateBy(ContextHolder.getUserId());
        existing.setUpdateTime(LocalDateTime.now());
        return updateById(existing);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(String uuid) {
        List<CatalogTag> tags = tagService.list(new QueryWrapper<CatalogTag>().lambda().eq(CatalogTag::getCategoryUuid, uuid));
        if (CollectionUtils.isNotEmpty(tags)) {
            List<String> tagUuids = tags.stream().map(CatalogTag::getUuid).collect(Collectors.toList());
            catalogEntityTagRelService.remove(new QueryWrapper<CatalogEntityTagRel>().lambda()
                    .in(CatalogEntityTagRel::getTagUuid, tagUuids));
            tagService.remove(new QueryWrapper<CatalogTag>().lambda().eq(CatalogTag::getCategoryUuid, uuid));
        }
        return remove(new QueryWrapper<CatalogTagCategory>().lambda().eq(CatalogTagCategory::getUuid, uuid));
    }

    @Override
    public List<CatalogTagCategory> listByWorkSpaceId(Long workSpaceId) {
        return list(new QueryWrapper<CatalogTagCategory>().lambda().eq(CatalogTagCategory::getWorkspaceId, workSpaceId));
    }

    private boolean isExistInWorkspace(Long workspaceId, String name, String excludeUuid) {
        QueryWrapper<CatalogTagCategory> qw = new QueryWrapper<>();
        qw.lambda().eq(CatalogTagCategory::getWorkspaceId, workspaceId).eq(CatalogTagCategory::getName, name);
        if (excludeUuid != null) {
            qw.lambda().ne(CatalogTagCategory::getUuid, excludeUuid);
        }
        return baseMapper.selectOne(qw) != null;
    }
}
