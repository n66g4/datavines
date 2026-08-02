import React, { useRef, useState } from 'react';
import {
    Button, Form, ModalProps, Select, Upload, message, Typography, Space,
} from 'antd';
import { UploadOutlined, DownloadOutlined } from '@ant-design/icons';
import { useIntl } from 'react-intl';
import {
    useModal, useImmutable, usePersistFn, useMount, useLoading,
} from '@/common';
import { $http } from '@/http';
import { useSelector } from '@/store';

type ShowArgs = {
    datasourceId: string | number;
};

type ImportResult = {
    created?: string[];
    updated?: string[];
    skipped?: string[];
    failed?: { name: string; reason: string }[];
};

const IndexInner = ({
    datasourceId,
    onDone,
}: {
    datasourceId: string | number;
    onDone: (result: ImportResult) => void;
}) => {
    const intl = useIntl();
    const [form] = Form.useForm();
    const setLoading = useLoading();
    const { workspaceId } = useSelector((r: any) => r.workSpaceReducer);
    const [errorStores, setErrorStores] = useState<{ label: string; value: number }[]>([]);
    const [fileList, setFileList] = useState<any[]>([]);

    useMount(async () => {
        try {
            const res: any[] = (await $http.get(`/errorDataStorage/list/${workspaceId}`)) || [];
            setErrorStores((res || []).map((item: any) => ({
                label: item.name,
                value: Number(item.id),
            })));
            const local = (res || []).find((i: any) => i.name === 'local-error-file');
            form.setFieldsValue({
                splitMode: 'PER_RULE',
                duplicateStrategy: 'SKIP',
                errorDataStorageId: local ? Number(local.id) : undefined,
            });
        } catch (e) {
            form.setFieldsValue({
                splitMode: 'PER_RULE',
                duplicateStrategy: 'SKIP',
            });
        }
    });

    const downloadTemplate = async () => {
        try {
            const blob: Blob = await $http.get('/job/batch-import/template.xlsx', {}, {
                responseType: 'blob',
            });
            const url = window.URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = 'job_batch_import_template.xlsx';
            a.click();
            window.URL.revokeObjectURL(url);
        } catch (e) {
            message.error(intl.formatMessage({ id: 'jobs_batch_import_template_fail' }));
        }
    };

    const onSubmit = usePersistFn(async () => {
        try {
            const values = await form.validateFields();
            if (!fileList.length) {
                message.warning(intl.formatMessage({ id: 'jobs_batch_import_file_required' }));
                return;
            }
            const file = fileList[0].originFileObj || fileList[0];
            const fd = new FormData();
            fd.append('file', file);
            fd.append('dataSourceId', String(datasourceId));
            fd.append('splitMode', values.splitMode);
            fd.append('duplicateStrategy', values.duplicateStrategy);
            fd.append('engineType', 'local');
            fd.append('runningNow', '0');
            if (values.errorDataStorageId) {
                fd.append('errorDataStorageId', String(values.errorDataStorageId));
            }
            setLoading(true);
            const res: ImportResult = await $http.post('/job/batch-import', fd, {
                timeout: 120000,
                headers: { 'Content-Type': undefined },
            });
            onDone(res || {});
        } catch (e: any) {
            message.error(e?.msg || e?.message || intl.formatMessage({ id: 'jobs_batch_import_fail' }));
        } finally {
            setLoading(false);
        }
    });

    return (
        <Form form={form} layout="vertical">
            <Form.Item
                label={intl.formatMessage({ id: 'jobs_batch_import_split' })}
                name="splitMode"
                rules={[{ required: true }]}
                tooltip={intl.formatMessage({ id: 'jobs_batch_import_split_tip' })}
            >
                <Select
                    options={[
                        { label: intl.formatMessage({ id: 'jobs_batch_import_split_rule' }), value: 'PER_RULE' },
                        { label: intl.formatMessage({ id: 'jobs_batch_import_split_table' }), value: 'PER_TABLE' },
                    ]}
                />
            </Form.Item>
            <Form.Item label={intl.formatMessage({ id: 'jobs_batch_import_dup' })} name="duplicateStrategy" rules={[{ required: true }]}>
                <Select
                    options={[
                        { label: intl.formatMessage({ id: 'jobs_batch_import_dup_skip' }), value: 'SKIP' },
                        { label: intl.formatMessage({ id: 'jobs_batch_import_dup_update' }), value: 'UPDATE' },
                        { label: intl.formatMessage({ id: 'jobs_batch_import_dup_fail' }), value: 'FAIL' },
                    ]}
                />
            </Form.Item>
            <Form.Item label={intl.formatMessage({ id: 'jobs_batch_import_error_store' })} name="errorDataStorageId">
                <Select
                    allowClear
                    options={errorStores}
                    placeholder={intl.formatMessage({ id: 'jobs_batch_import_error_store_tip' })}
                />
            </Form.Item>
            <Form.Item label={intl.formatMessage({ id: 'jobs_batch_import_file' })} required>
                <Upload
                    beforeUpload={() => false}
                    maxCount={1}
                    accept=".xlsx,.xls"
                    fileList={fileList}
                    onChange={({ fileList: fl }) => setFileList(fl)}
                >
                    <Button icon={<UploadOutlined />}>{intl.formatMessage({ id: 'jobs_batch_import_select_file' })}</Button>
                </Upload>
            </Form.Item>
            <Space style={{ marginBottom: 16 }}>
                <Button icon={<DownloadOutlined />} onClick={() => downloadTemplate()}>
                    {intl.formatMessage({ id: 'jobs_batch_import_tpl_xlsx' })}
                </Button>
            </Space>
            <div style={{ textAlign: 'right' }}>
                <Button type="primary" onClick={onSubmit}>
                    {intl.formatMessage({ id: 'jobs_batch_import_submit' })}
                </Button>
            </div>
        </Form>
    );
};

export const useBatchImportJobsModal = (options: ModalProps & { afterImport?: () => void }) => {
    const intl = useIntl();
    const argsRef = useRef<ShowArgs | null>(null);
    const afterImport = options.afterImport;

    const {
        Render, hide, show, ...rest
    } = useModal<any>({
        title: intl.formatMessage({ id: 'jobs_batch_import' }),
        width: 640,
        footer: null,
        ...(options || {}),
        afterClose() {
            argsRef.current = null;
        },
    });

    const onDone = usePersistFn((result: ImportResult) => {
        const created = result.created?.length || 0;
        const updated = result.updated?.length || 0;
        const skipped = result.skipped?.length || 0;
        const failed = result.failed?.length || 0;
        message.success(
            intl.formatMessage(
                { id: 'jobs_batch_import_result' },
                {
                    created, updated, skipped, failed,
                },
            ),
        );
        if (failed > 0 && result.failed) {
            const detail = result.failed.slice(0, 5).map((f) => `${f.name}: ${f.reason}`).join('; ');
            message.warning(detail);
        }
        hide();
        afterImport && afterImport();
    });

    return {
        Render: useImmutable(() => (
            <Render>
                {argsRef.current ? (
                    <IndexInner
                        datasourceId={argsRef.current.datasourceId}
                        onDone={onDone}
                    />
                ) : (
                    <Typography.Text type="secondary">...</Typography.Text>
                )}
            </Render>
        )),
        show(args: ShowArgs) {
            argsRef.current = args;
            show(args);
        },
        hide,
        ...rest,
    };
};
