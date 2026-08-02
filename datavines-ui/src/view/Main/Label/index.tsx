import React, { useState, useEffect, useMemo } from 'react';
import {
    List, Tooltip, Button, Modal, Form, Input, message, Popconfirm, Empty, Space,
} from 'antd';
import './index.less';
import { PlusOutlined, DeleteOutlined, EditOutlined, SearchOutlined } from '@ant-design/icons';
import { useIntl } from 'react-intl';
import { $http } from '@/http';
import { useSelector } from '@/store';

type Category = {
    id: number;
    name: string;
    uuid: string;
};

type TagItem = {
    name: string;
    uuid: string;
    entityCount?: number;
};

const Index = () => {
    const intl = useIntl();
    const { workspaceId } = useSelector((r) => r.workSpaceReducer);
    const [isModalOpen, setIsModalOpen] = useState(false);
    const [type, setType] = useState<'category' | 'tag'>('category');
    const [editingUuid, setEditingUuid] = useState<string | null>(null);
    const [form] = Form.useForm();
    const [tagCategoryList, setTagCategoryList] = useState<Category[]>([]);
    const [tagList, setTagList] = useState<TagItem[]>([]);
    const [currentIndex, setCurrentIndex] = useState(0);
    const [categoryKeyword, setCategoryKeyword] = useState('');
    const [tagKeyword, setTagKeyword] = useState('');
    const [loading, setLoading] = useState(false);

    const filteredCategories = useMemo(() => {
        const kw = categoryKeyword.trim().toLowerCase();
        if (!kw) return tagCategoryList;
        return tagCategoryList.filter((c) => (c.name || '').toLowerCase().includes(kw));
    }, [tagCategoryList, categoryKeyword]);

    const filteredTags = useMemo(() => {
        const kw = tagKeyword.trim().toLowerCase();
        if (!kw) return tagList;
        return tagList.filter((t) => (t.name || '').toLowerCase().includes(kw));
    }, [tagList, tagKeyword]);

    const getTagList = async (categoryUUID: string) => {
        if (!categoryUUID) {
            setTagList([]);
            return;
        }
        const res = await $http.get(`catalog/tag/list-in-category/${categoryUUID}`);
        setTagList(Array.isArray(res) ? res : []);
    };

    const getList = async (preferUuid?: string) => {
        if (!workspaceId) {
            setTagCategoryList([]);
            setTagList([]);
            return;
        }
        setLoading(true);
        try {
            const res: Category[] = await $http.get(`catalog/tag/category/list/${workspaceId}`);
            const list = Array.isArray(res) ? res : [];
            setTagCategoryList(list);
            if (list.length === 0) {
                setCurrentIndex(0);
                setTagList([]);
                return;
            }
            let idx = 0;
            if (preferUuid) {
                const found = list.findIndex((c) => c.uuid === preferUuid);
                if (found >= 0) idx = found;
            } else if (currentIndex < list.length) {
                idx = currentIndex;
            }
            setCurrentIndex(idx);
            await getTagList(list[idx].uuid);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        getList();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [workspaceId]);

    const showModal = (modalType: 'category' | 'tag', record?: { uuid: string; name: string }) => {
        setType(modalType);
        setEditingUuid(record?.uuid || null);
        form.setFieldsValue({ name: record?.name || '' });
        setIsModalOpen(true);
    };

    const deleteCategory = async (categoryUUID: string) => {
        await $http.delete(`catalog/tag/category/${categoryUUID}`);
        message.success(intl.formatMessage({ id: 'common_success' }));
        await getList();
    };

    const deleteTag = async (tagUUID: string) => {
        await $http.delete(`catalog/tag/${tagUUID}`);
        message.success(intl.formatMessage({ id: 'common_success' }));
        if (tagCategoryList[currentIndex]) {
            await getTagList(tagCategoryList[currentIndex].uuid);
        }
    };

    const handleOk = async () => {
        try {
            const value = await form.validateFields();
            if (type === 'category') {
                if (editingUuid) {
                    await $http.put('/catalog/tag/category', { uuid: editingUuid, name: value.name });
                } else {
                    await $http.post('/catalog/tag/category', { ...value, workspaceId });
                }
                message.success(intl.formatMessage({ id: 'common_success' }));
                await getList(editingUuid || undefined);
            } else {
                if (!tagCategoryList[currentIndex]) {
                    message.warning('请先选择标签分类');
                    return;
                }
                if (editingUuid) {
                    await $http.put('/catalog/tag/', { uuid: editingUuid, name: value.name });
                } else {
                    await $http.post('/catalog/tag/', {
                        name: value.name,
                        categoryUuid: tagCategoryList[currentIndex].uuid,
                    });
                }
                message.success(intl.formatMessage({ id: 'common_success' }));
                await getTagList(tagCategoryList[currentIndex].uuid);
            }
            setIsModalOpen(false);
            setEditingUuid(null);
            form.resetFields();
        } catch (e) {
            // validation or request error already toasted by http layer when applicable
        }
    };

    const handleCancel = () => {
        form.resetFields();
        setEditingUuid(null);
        setIsModalOpen(false);
    };

    const getCurrentTag = (indexInFullList: number) => {
        setCurrentIndex(indexInFullList);
        getTagList(tagCategoryList[indexInFullList].uuid);
    };

    const modalTitle = () => {
        if (type === 'category') {
            return editingUuid
                ? intl.formatMessage({ id: 'label_edit_category' })
                : intl.formatMessage({ id: 'label_add_category' });
        }
        return editingUuid
            ? intl.formatMessage({ id: 'label_edit' })
            : intl.formatMessage({ id: 'label_add' });
    };

    return (
        <div className="dv-label dv-page-padding">
            <div>
                <p className="dv-label-title">
                    {intl.formatMessage({ id: 'label_title' })}
                    <Tooltip title={intl.formatMessage({ id: 'label_add_category' })}>
                        <Button
                            onClick={() => showModal('category')}
                            className="fr"
                            size="small"
                            style={{ marginTop: '6px' }}
                            shape="circle"
                            icon={<PlusOutlined />}
                        />
                    </Tooltip>
                </p>
                <Input
                    allowClear
                    prefix={<SearchOutlined />}
                    placeholder={intl.formatMessage({ id: 'label_search_category' })}
                    value={categoryKeyword}
                    onChange={(e) => setCategoryKeyword(e.target.value)}
                    style={{ marginBottom: 12 }}
                />
                {filteredCategories.length === 0 ? (
                    <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={intl.formatMessage({ id: 'label_empty_category' })} />
                ) : (
                    filteredCategories.map((item) => {
                        const fullIndex = tagCategoryList.findIndex((c) => c.uuid === item.uuid);
                        return (
                            <div
                                key={item.uuid}
                                onClick={() => getCurrentTag(fullIndex)}
                                className={currentIndex === fullIndex ? ' actived category-item ' : 'category-item'}
                            >
                                <span className="category-name">{item.name || '  '}</span>
                                <Space size={0} onClick={(e) => e.stopPropagation()}>
                                    <Button type="text" icon={<EditOutlined />} onClick={() => showModal('category', item)} />
                                    <Popconfirm
                                        title={intl.formatMessage({ id: 'common_delete_tip' })}
                                        onConfirm={() => deleteCategory(item.uuid)}
                                        okText={intl.formatMessage({ id: 'common_Ok' })}
                                        cancelText={intl.formatMessage({ id: 'common_Cancel' })}
                                    >
                                        <Button type="text" icon={<DeleteOutlined />} danger />
                                    </Popconfirm>
                                </Space>
                            </div>
                        );
                    })
                )}
            </div>
            <div>
                <p className="dv-label-title">
                    {intl.formatMessage({ id: 'label_list' })}
                    <Tooltip title={intl.formatMessage({ id: 'label_add' })}>
                        <Button
                            disabled={tagCategoryList.length === 0}
                            onClick={() => showModal('tag')}
                            className="fr"
                            size="small"
                            style={{ marginTop: '6px' }}
                            shape="circle"
                            icon={<PlusOutlined />}
                        />
                    </Tooltip>
                </p>
                <Input
                    allowClear
                    prefix={<SearchOutlined />}
                    placeholder={intl.formatMessage({ id: 'label_search_tag' })}
                    value={tagKeyword}
                    onChange={(e) => setTagKeyword(e.target.value)}
                    style={{ marginBottom: 12 }}
                    disabled={tagCategoryList.length === 0}
                />
                <List
                    loading={loading}
                    dataSource={filteredTags}
                    locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={intl.formatMessage({ id: 'label_empty_tag' })} /> }}
                    renderItem={(item) => (
                        <List.Item key={item.uuid}>
                            <div className="category-item" style={{ padding: '0px 20px 0px 0px' }}>
                                <span>
                                    {item.name}
                                    <span className="tag-ref-count">
                                        {intl.formatMessage({ id: 'label_entity_count' }, { count: item.entityCount ?? 0 })}
                                    </span>
                                </span>
                                <Space size={0}>
                                    <Button type="link" icon={<EditOutlined />} onClick={() => showModal('tag', item)} />
                                    <Popconfirm
                                        title={intl.formatMessage({ id: 'common_delete_tip' })}
                                        onConfirm={() => deleteTag(item.uuid)}
                                        okText={intl.formatMessage({ id: 'common_Ok' })}
                                        cancelText={intl.formatMessage({ id: 'common_Cancel' })}
                                    >
                                        <Button type="link" icon={<DeleteOutlined />} danger />
                                    </Popconfirm>
                                </Space>
                            </div>
                        </List.Item>
                    )}
                />
            </div>
            <Modal title={modalTitle()} open={isModalOpen} onOk={handleOk} onCancel={handleCancel} destroyOnClose>
                <Form
                    labelCol={{ span: 5 }}
                    wrapperCol={{ span: 18 }}
                    autoComplete="off"
                    form={form}
                >
                    <Form.Item
                        label={intl.formatMessage({ id: 'label_name' })}
                        name="name"
                        rules={[{ required: true, message: intl.formatMessage({ id: 'common_input_tip' }) }]}
                    >
                        <Input autoComplete="off" />
                    </Form.Item>
                </Form>
            </Modal>
        </div>
    );
};

export default Index;
