import React, { useEffect, useState } from 'react';
import {
    Table, Button, Modal, Form, Input, message, Space, DatePicker, Select, Checkbox,
} from 'antd';
import { MinusCircleOutlined, PlusOutlined } from '@ant-design/icons';
import { useIntl } from 'react-intl';
import dayjs from 'dayjs';
import { $http } from '@/http';
import { useSelector } from '@/store';
import { useMount } from '@/common';
import Title from '@/component/Title';

const EMPTY_MAPPING = { cnName: '', byDs: {} as Record<string, string> };

type DsOption = { label: string; value: number };
type TableOption = { label: string; value: string; uuid?: string };
type TagOption = { label: string; value: string };

const Index = () => {
    const intl = useIntl();
    const { workspaceId } = useSelector((r) => r.workSpaceReducer);
    const [loading, setLoading] = useState(false);
    const [profiles, setProfiles] = useState<any[]>([]);
    const [runs, setRuns] = useState<any[]>([]);
    const [runTotal, setRunTotal] = useState(0);
    const [pageParams, setPageParams] = useState({ pageNumber: 1, pageSize: 10 });
    const [editOpen, setEditOpen] = useState(false);
    const [runOpen, setRunOpen] = useState(false);
    const [editing, setEditing] = useState<any>(null);
    const [form] = Form.useForm();
    const [runForm] = Form.useForm();
    const [dsOptions, setDsOptions] = useState<DsOption[]>([]);
    const [tableOptionsMap, setTableOptionsMap] = useState<Record<number, TableOption[]>>({});
    const [tableLoadingMap, setTableLoadingMap] = useState<Record<number, boolean>>({});
    const [tagOptions, setTagOptions] = useState<TagOption[]>([]);
    const selectedDsIds: number[] = Form.useWatch('datasourceIdList', form) || [];

    const loadProfiles = async () => {
        const list = await $http.get(`/ops-report/profile/list/${workspaceId}`);
        setProfiles(Array.isArray(list) ? list : []);
    };

    const loadRuns = async () => {
        setLoading(true);
        try {
            const res = await $http.get('/ops-report/run/page', {
                workspaceId, ...pageParams,
            });
            setRuns(res?.records || []);
            setRunTotal(res?.total || 0);
        } finally {
            setLoading(false);
        }
    };

    const loadDatasources = async () => {
        const res = await $http.get('/datasource/page', {
            workSpaceId: workspaceId,
            pageNumber: 1,
            pageSize: 9999,
        });
        const opts = (res?.records || []).map((d: any) => ({
            label: `${d.name} (ID:${d.id})`,
            value: Number(d.id),
        }));
        setDsOptions(opts);
    };

    const loadTags = async (extraNames: string[] = []) => {
        if (!workspaceId) {
            setTagOptions([]);
            return [] as TagOption[];
        }
        const res = await $http.get(`/catalog/tag/list-in-workspace/${workspaceId}`);
        const list = Array.isArray(res) ? res : (Array.isArray(res?.data) ? res.data : []);
        const names = new Set<string>();
        list.forEach((t: any) => {
            if (t?.name) {
                names.add(String(t.name));
            }
        });
        extraNames.forEach((n) => {
            if (n) {
                names.add(String(n));
            }
        });
        const opts = [...names].map((n) => ({ label: n, value: n }));
        setTagOptions(opts);
        return opts;
    };

    const loadTablesForDs = async (dsId: number) => {
        if (tableOptionsMap[dsId]?.length) {
            return;
        }
        setTableLoadingMap((m) => ({ ...m, [dsId]: true }));
        try {
            const databases = await $http.get(`/datasource/${dsId}/databases`);
            const dbList = Array.isArray(databases) ? databases : [];
            const allTables: TableOption[] = [];
            const seen = new Set<string>();
            for (const db of dbList) {
                const dbName = db?.name || db;
                if (!dbName) {
                    continue;
                }
                try {
                    const tables = await $http.get(`/datasource/${dsId}/${dbName}/tables`);
                    (tables || []).forEach((t: any) => {
                        const name = t?.name || t;
                        if (name && !seen.has(name)) {
                            seen.add(name);
                            allTables.push({
                                label: dbList.length > 1 ? `${dbName}.${name}` : name,
                                value: name,
                                uuid: t?.uuid,
                            });
                        }
                    });
                } catch (e) {
                    // ignore single db failure
                }
            }
            allTables.sort((a, b) => a.value.localeCompare(b.value));
            setTableOptionsMap((m) => ({ ...m, [dsId]: allTables }));
        } finally {
            setTableLoadingMap((m) => ({ ...m, [dsId]: false }));
        }
    };

    const fillCnNameFromTable = async (mappingIndex: number, tableName?: string, tableUuid?: string) => {
        if (!tableName) {
            return;
        }
        let cn = tableName;
        if (tableUuid) {
            try {
                const detail = await $http.get(`/catalog/detail/table/${tableUuid}`);
                if (detail?.comment) {
                    cn = String(detail.comment);
                }
            } catch (e) {
                // keep table name
            }
        }
        form.setFieldValue(['tableMappings', mappingIndex, 'cnName'], cn);
    };

    useMount(() => {
        loadProfiles();
        loadRuns();
        loadDatasources();
    });

    useEffect(() => {
        loadRuns();
    }, [pageParams]);

    useEffect(() => {
        (selectedDsIds || []).forEach((id) => {
            if (id != null) {
                loadTablesForDs(Number(id));
            }
        });
    }, [selectedDsIds]);

    useEffect(() => {
        if (editOpen) {
            loadTags();
        }
    }, [editOpen, workspaceId]);

    const parseDatasourceIds = (raw: any): number[] => {
        if (Array.isArray(raw)) {
            return raw.map(Number).filter((n) => !Number.isNaN(n));
        }
        if (typeof raw === 'string' && raw.trim()) {
            try {
                const arr = JSON.parse(raw);
                return Array.isArray(arr) ? arr.map(Number).filter((n) => !Number.isNaN(n)) : [];
            } catch {
                return [];
            }
        }
        return [];
    };

    const parseTableMappings = (configJson: any, dsIds: number[]) => {
        let cfg: any = {};
        if (typeof configJson === 'string' && configJson.trim()) {
            try {
                cfg = JSON.parse(configJson);
            } catch {
                cfg = {};
            }
        } else if (configJson && typeof configJson === 'object') {
            cfg = configJson;
        }
        const tables = (cfg.tables || []).map((t: any) => {
            const byDs: Record<string, string> = {};
            dsIds.forEach((id) => {
                const key = String(id);
                if (t.byDatasource && t.byDatasource[key]) {
                    byDs[key] = t.byDatasource[key];
                }
            });
            return {
                cnName: t.cnName || '',
                byDs,
            };
        });
        const legacyBiz = (cfg.tables || []).map((t: any) => t.bizName).filter(Boolean);
        const businessTag = cfg.businessTag || legacyBiz[0] || undefined;
        return {
            businessTag,
            tables: tables.length ? tables : [{ ...EMPTY_MAPPING }],
            timeFields: (() => {
                const tf = cfg.timeFields;
                if (Array.isArray(tf) && tf.length) {
                    return tf;
                }
                if (typeof tf === 'string' && tf.trim()) {
                    return tf.split(',').map((s: string) => s.trim()).filter(Boolean);
                }
                return ['create_time', 'update_time'];
            })(),
            errorSampleLimit: cfg.errorSampleLimit ?? 50,
        };
    };

    const openCreate = async () => {
        setEditing(null);
        await loadTags();
        form.setFieldsValue({
            name: '',
            businessTag: undefined,
            datasourceIdList: [],
            tableMappings: [{ ...EMPTY_MAPPING }],
            timeFields: ['create_time', 'update_time'],
            errorSampleLimit: 50,
            scheduleCron: '',
        });
        setEditOpen(true);
    };

    const openEdit = async (record: any) => {
        setEditing(record);
        const dsIds = parseDatasourceIds(record.datasourceIds);
        const parsed = parseTableMappings(record.configJson, dsIds);
        let tag = parsed.businessTag || record.businessType;
        if (tag === 'DEFAULT') {
            tag = parsed.businessTag;
        }
        await loadTags([tag].filter(Boolean));
        form.setFieldsValue({
            name: record.name,
            businessTag: tag || undefined,
            datasourceIdList: dsIds,
            tableMappings: parsed.tables,
            timeFields: parsed.timeFields,
            errorSampleLimit: parsed.errorSampleLimit,
            scheduleCron: record.scheduleCron || '',
        });
        dsIds.forEach((id) => loadTablesForDs(id));
        setEditOpen(true);
    };

    const buildPayload = (values: any) => {
        const dsIds: number[] = (values.datasourceIdList || []).map(Number);
        const businessTag = values.businessTag;
        const tables = (values.tableMappings || []).map((row: any) => {
            const byDatasource: Record<string, string> = {};
            dsIds.forEach((id) => {
                const table = row?.byDs?.[String(id)];
                if (table) {
                    byDatasource[String(id)] = table;
                }
            });
            return {
                cnName: row.cnName,
                byDatasource,
            };
        }).filter((t: any) => t.cnName && Object.keys(t.byDatasource).length > 0);

        const timeFields = (Array.isArray(values.timeFields) ? values.timeFields : [])
            .map((s: string) => String(s || '').trim())
            .filter(Boolean);

        return {
            name: values.name,
            businessType: businessTag,
            scheduleCron: values.scheduleCron || '',
            datasourceIds: JSON.stringify(dsIds),
            configJson: JSON.stringify({
                businessTag,
                tables,
                timeFields: timeFields.length ? timeFields : ['create_time', 'update_time'],
                errorSampleLimit: Number(values.errorSampleLimit) || 50,
            }, null, 2),
        };
    };

    const saveProfile = async () => {
        const values = await form.validateFields();
        if (!(values.datasourceIdList || []).length) {
            message.error('请选择至少一个下级部门数据源');
            return;
        }
        if (!values.businessTag) {
            message.error('请选择业务标签');
            return;
        }
        const payload = buildPayload(values);
        if (!JSON.parse(payload.configJson).tables?.length) {
            message.error('请至少配置一条有效表映射（物理表 + 中文表名）');
            return;
        }
        if (editing?.id) {
            await $http.put('/ops-report/profile', { ...payload, id: editing.id, workspaceId });
        } else {
            await $http.post('/ops-report/profile', { ...payload, workspaceId });
        }
        message.success(intl.formatMessage({ id: 'common_success' }));
        setEditOpen(false);
        loadProfiles();
    };

    const removeProfile = async (id: number) => {
        await $http.delete(`/ops-report/profile/${id}`);
        message.success(intl.formatMessage({ id: 'common_success' }));
        loadProfiles();
    };

    const openRun = (record: any) => {
        runForm.setFieldsValue({
            profileId: record.id,
            statDate: dayjs(),
        });
        setRunOpen(true);
    };

    const doRun = async () => {
        const values = await runForm.validateFields();
        const runId = await $http.post('/ops-report/run', {
            profileId: values.profileId,
            statDate: values.statDate ? values.statDate.format('YYYY-MM-DD') : undefined,
        });
        message.success(`生成成功，运行ID=${runId}`);
        setRunOpen(false);
        loadRuns();
    };

    const download = async (runId: number, kind: 'ledger' | 'checklists') => {
        try {
            const blob: Blob = await $http.get(`/ops-report/run/${runId}/${kind}`, {}, {
                responseType: 'blob',
            });
            if (blob.type && blob.type.includes('application/json')) {
                const text = await blob.text();
                const err = JSON.parse(text);
                throw new Error(err?.msg || '下载失败');
            }
            const a = document.createElement('a');
            a.href = URL.createObjectURL(blob);
            a.download = kind === 'ledger' ? `ops_report_ledger_${runId}.xlsx` : `ops_report_checklists_${runId}.zip`;
            a.click();
            URL.revokeObjectURL(a.href);
        } catch (e: any) {
            message.error(e?.msg || e?.message || String(e));
        }
    };

    const removeRun = (record: any) => {
        let deleteFiles = false;
        Modal.confirm({
            title: `删除生成历史 #${record.id}`,
            content: (
                <Checkbox
                    defaultChecked={false}
                    onChange={(e) => {
                        deleteFiles = e.target.checked;
                    }}
                >
                    同时删除本地文件（台账 / 整改清单）
                </Checkbox>
            ),
            okText: '删除',
            okButtonProps: { danger: true },
            cancelText: '取消',
            onOk: async () => {
                await $http.delete(`/ops-report/run/${record.id}?deleteFiles=${deleteFiles}`);
                message.success(intl.formatMessage({ id: 'common_success' }));
                loadRuns();
            },
        });
    };

    const dsNameById = (id: number) => {
        const found = dsOptions.find((d) => d.value === id);
        return found ? found.label : `数据源${id}`;
    };

    const profileCols = [
        { title: 'ID', dataIndex: 'id', width: 80 },
        { title: '名称', dataIndex: 'name' },
        { title: '业务标签', dataIndex: 'businessType', width: 160 },
        {
            title: '下级部门数据源',
            dataIndex: 'datasourceIds',
            ellipsis: true,
            render: (text: string) => {
                const ids = parseDatasourceIds(text);
                if (!ids.length) return text;
                return ids.map((id) => {
                    const opt = dsOptions.find((d) => d.value === id);
                    return opt ? opt.label : id;
                }).join('；');
            },
        },
        { title: 'Cron', dataIndex: 'scheduleCron', width: 140 },
        {
            title: intl.formatMessage({ id: 'common_action' }),
            width: 260,
            render: (_: any, record: any) => (
                <Space>
                    <Button type="link" onClick={() => openRun(record)}>生成</Button>
                    <Button type="link" onClick={() => openEdit(record)}>编辑</Button>
                    <Button type="link" danger onClick={() => removeProfile(record.id)}>删除</Button>
                </Space>
            ),
        },
    ];

    const runCols = [
        { title: '运行ID', dataIndex: 'id', width: 80 },
        { title: '定义ID', dataIndex: 'profileId', width: 80 },
        { title: '统计日', dataIndex: 'statDate', width: 120 },
        { title: '状态', dataIndex: 'status', width: 100 },
        { title: '信息', dataIndex: 'message', ellipsis: true },
        { title: '创建时间', dataIndex: 'createTime', width: 170 },
        {
            title: intl.formatMessage({ id: 'common_action' }),
            width: 280,
            render: (_: any, record: any) => (
                <Space>
                    <Button type="link" disabled={record.status !== 'SUCCESS'} onClick={() => download(record.id, 'ledger')}>台账</Button>
                    <Button type="link" disabled={!record.checklistZipPath} onClick={() => download(record.id, 'checklists')}>整改清单</Button>
                    <Button type="link" danger onClick={() => removeRun(record)}>删除</Button>
                </Space>
            ),
        },
    ];

    return (
        <div style={{ padding: 16 }}>
            <Title>{intl.formatMessage({ id: '/main/opsReport' })}</Title>
            <div style={{ marginBottom: 12 }}>
                <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建报表定义</Button>
            </div>
            <Table rowKey="id" columns={profileCols as any} dataSource={profiles} pagination={false} style={{ marginBottom: 24 }} />
            <Title>生成历史</Title>
            <Table
                rowKey="id"
                loading={loading}
                columns={runCols as any}
                dataSource={runs}
                pagination={{
                    current: pageParams.pageNumber,
                    pageSize: pageParams.pageSize,
                    total: runTotal,
                    onChange: (pageNumber, pageSize) => setPageParams({ pageNumber, pageSize: pageSize || 10 }),
                }}
            />

            <Modal
                title={editing ? '编辑报表定义' : '新建报表定义'}
                open={editOpen}
                onOk={saveProfile}
                onCancel={() => setEditOpen(false)}
                width={960}
                destroyOnClose
                styles={{ body: { maxHeight: '70vh', overflowY: 'auto' } }}
            >
                <Form form={form} layout="vertical">
                    <Form.Item name="name" label="名称" rules={[{ required: true }]}><Input /></Form.Item>
                    <Form.Item
                        name="businessTag"
                        label="业务标签"
                        rules={[{ required: true, message: '请选择业务标签' }]}
                        extra={tagOptions.length === 0 ? '请先在标签管理中创建标签；作业需打上相同标签才会进入本报表' : '一套报表对应一个业务标签；仅统计打了该标签的质检作业'}
                    >
                        <Select
                            showSearch
                            allowClear
                            optionFilterProp="label"
                            placeholder={tagOptions.length ? '请选择业务标签' : '暂无标签'}
                            options={tagOptions}
                            disabled={tagOptions.length === 0}
                        />
                    </Form.Item>
                    <Form.Item
                        name="datasourceIdList"
                        label="下级部门数据源"
                        rules={[{ required: true, message: '请选择下级部门数据源' }]}
                        extra="一市一个数据源，可多选"
                    >
                        <Select
                            mode="multiple"
                            allowClear
                            showSearch
                            optionFilterProp="label"
                            placeholder="请选择数据源"
                            options={dsOptions}
                        />
                    </Form.Item>

                    <Form.Item
                        label="表映射"
                        required
                        extra="先选下级部门物理表；中文表名从物理表注释自动带出，可改"
                    >
                        <Form.List name="tableMappings">
                            {(fields, { add, remove }) => (
                                <>
                                    {fields.map((field) => (
                                        <div
                                            key={field.key}
                                            style={{
                                                border: '1px solid #f0f0f0',
                                                borderRadius: 6,
                                                padding: 12,
                                                marginBottom: 12,
                                                background: '#fafafa',
                                            }}
                                        >
                                            {(selectedDsIds || []).length === 0 ? (
                                                <div style={{ color: '#999', marginBottom: 8 }}>请先选择下级部门数据源</div>
                                            ) : (
                                                <Space wrap style={{ marginBottom: 8 }}>
                                                    {(selectedDsIds || []).map((dsId) => (
                                                        <Form.Item
                                                            key={`${field.key}-${dsId}`}
                                                            name={[field.name, 'byDs', String(dsId)]}
                                                            label={dsNameById(dsId)}
                                                            style={{ marginBottom: 0, minWidth: 240 }}
                                                            rules={[{ required: true, message: '请选择表' }]}
                                                        >
                                                            <Select
                                                                showSearch
                                                                allowClear
                                                                optionFilterProp="label"
                                                                placeholder="选择物理表"
                                                                loading={!!tableLoadingMap[dsId]}
                                                                options={tableOptionsMap[dsId] || []}
                                                                style={{ width: 240 }}
                                                                getPopupContainer={(n) => n.parentElement || document.body}
                                                                onChange={(tableName: string, option: any) => {
                                                                    const opt = Array.isArray(option) ? option[0] : option;
                                                                    fillCnNameFromTable(field.name, tableName, opt?.uuid);
                                                                }}
                                                            />
                                                        </Form.Item>
                                                    ))}
                                                </Space>
                                            )}
                                            <Space align="start" wrap style={{ width: '100%' }}>
                                                <Form.Item
                                                    name={[field.name, 'cnName']}
                                                    label="中文表名"
                                                    rules={[{ required: true, message: '请先选择物理表以带出注释' }]}
                                                    style={{ marginBottom: 8, minWidth: 260 }}
                                                    extra="选物理表后自动填充，可改"
                                                >
                                                    <Input placeholder="选择物理表后自动填充" />
                                                </Form.Item>
                                                <Button
                                                    type="text"
                                                    danger
                                                    icon={<MinusCircleOutlined />}
                                                    onClick={() => remove(field.name)}
                                                    style={{ marginTop: 30 }}
                                                />
                                            </Space>
                                        </div>
                                    ))}
                                    <Button
                                        type="dashed"
                                        onClick={() => add({ ...EMPTY_MAPPING })}
                                        block
                                        icon={<PlusOutlined />}
                                    >
                                        添加表映射
                                    </Button>
                                </>
                            )}
                        </Form.List>
                    </Form.Item>

                    <Form.Item
                        name="timeFields"
                        label="时间字段"
                        extra="可选；用于报送最新时间。默认已选 entrytime、update_time，可增删"
                    >
                        <Select
                            mode="tags"
                            allowClear
                            placeholder="选择或输入时间字段"
                            tokenSeparators={[',']}
                            options={[
                                { label: 'create_time', value: 'create_time' },
                                { label: 'update_time', value: 'update_time' },
                            ]}
                        />
                    </Form.Item>
                    <Form.Item name="errorSampleLimit" label="概况样例行数" initialValue={50}>
                        <Input type="number" min={1} />
                    </Form.Item>
                    <Form.Item name="scheduleCron" label="定时Cron(可选)" extra="例如 0 30 2 * * ? 表示每天 02:30">
                        <Input placeholder="留空则仅手动生成" />
                    </Form.Item>
                </Form>
            </Modal>

            <Modal title="生成统计报表" open={runOpen} onOk={doRun} onCancel={() => setRunOpen(false)} destroyOnClose>
                <Form form={runForm} layout="vertical">
                    <Form.Item name="profileId" label="定义ID" rules={[{ required: true }]}><Input disabled /></Form.Item>
                    <Form.Item name="statDate" label="统计日期" rules={[{ required: true }]}>
                        <DatePicker style={{ width: '100%' }} />
                    </Form.Item>
                </Form>
            </Modal>
        </div>
    );
};

export default Index;
