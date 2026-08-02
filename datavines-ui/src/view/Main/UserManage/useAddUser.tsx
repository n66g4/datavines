import React, { useRef, useState, useImperativeHandle } from 'react';
import {
    Input, ModalProps, Form, FormInstance, message, Select,
} from 'antd';
import { useIntl } from 'react-intl';
import {
    useModal, useImmutable, FormRender, IFormRender, usePersistFn, useLoading,
} from '@/common';
import { $http } from '@/http';
import { useSelector } from '@/store';
import { TUserItem } from '@/type/User';
import { PWD_REG, EMAIL_REG } from '@/utils/constants';

type InnerProps = {
    form: FormInstance,
    detail?: null | TUserItem,
    innerRef?: any
}

export const CreateUserComponent = ({ form, detail, innerRef }: InnerProps) => {
    const intl = useIntl();
    const setBodyLoading = useLoading();
    const { workspaceId } = useSelector((r) => r.workSpaceReducer);
    const inputTip = intl.formatMessage({ id: 'common_input_tip' });
    const requiredTop = intl.formatMessage({ id: 'common_required_tip' });
    const patternTip = intl.formatMessage({ id: 'common_input_pattern_tip' });
    const schema: IFormRender = {
        name: 'user-create-form',
        layout: 'vertical',
        column: 1,
        gutter: 20,
        formItemProps: {
            style: { marginBottom: 10 },
        },
        meta: [
            {
                label: intl.formatMessage({ id: 'userName_text' }),
                name: 'username',
                initialValue: detail?.username,
                rules: [
                    { required: true, message: requiredTop },
                    { pattern: /^[\u4E00-\u9FA5_a-zA-Z0-9]{2,32}$/, message: patternTip },
                ],
                widget: <Input autoComplete="off" placeholder={`${inputTip}${intl.formatMessage({ id: 'userName_text' })}`} />,
            },
            {
                label: intl.formatMessage({ id: 'email_text' }),
                name: 'email',
                initialValue: detail?.email,
                rules: [
                    { required: true, message: requiredTop },
                    { pattern: EMAIL_REG, message: patternTip },
                ],
                widget: <Input autoComplete="off" />,
            },
            {
                label: intl.formatMessage({ id: 'password_text' }),
                name: 'password',
                rules: [
                    { required: true, message: requiredTop },
                    { pattern: PWD_REG, message: intl.formatMessage({ id: 'password_tip' }) },
                ],
                widget: <Input.Password autoComplete="new-password" placeholder={intl.formatMessage({ id: 'password_tip' })} />,
            },
            {
                label: intl.formatMessage({ id: 'phone_text' }),
                name: 'phone',
                widget: <Input autoComplete="off" />,
            },
            {
                label: intl.formatMessage({ id: 'workspace_user_role' }),
                name: 'roleId',
                initialValue: 2,
                rules: [{ required: true, message: requiredTop }],
                widget: (
                    <Select
                        options={[
                            { value: 1, label: intl.formatMessage({ id: 'workspace_role_admin' }) },
                            { value: 2, label: intl.formatMessage({ id: 'workspace_role_member' }) },
                        ]}
                    />
                ),
            },
        ],
    };
    useImperativeHandle(innerRef, () => ({
        saveUpdate(hide?: () => any) {
            form.validateFields().then(async (values) => {
                try {
                    setBodyLoading(true);
                    await $http.post('/workspace/createUser', {
                        workspaceId,
                        ...values,
                    });
                    message.success(intl.formatMessage({ id: 'common_success' }));
                    form.resetFields();
                    if (hide) {
                        hide();
                    }
                } catch (error) {
                } finally {
                    setBodyLoading(false);
                }
            }).catch(() => {});
        },
    }));
    return <FormRender {...schema} form={form} />;
};

export const useAddUser = (options: ModalProps) => {
    const [form] = Form.useForm();
    const intl = useIntl();
    const innerRef = useRef<any>();
    const [editInfo, setEditInfo] = useState<TUserItem | null>(null);
    const editRef = useRef<TUserItem | null>(null);
    editRef.current = editInfo;

    const onOk = usePersistFn(async () => {
        innerRef.current.saveUpdate(hide);
    });
    const {
        Render, hide, show, ...rest
    } = useModal<any>({
        title: intl.formatMessage({ id: 'workspace_user_create' }),
        onOk,
        ...(options || {}),
    });
    return {
        Render: useImmutable(() => (<Render><CreateUserComponent innerRef={innerRef} form={form} detail={editRef.current} /></Render>)),
        show(data: any) {
            setEditInfo(data);
            form.resetFields();
            show(data);
        },
        ...rest,
    };
};
