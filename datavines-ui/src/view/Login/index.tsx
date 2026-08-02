import React, { useState } from 'react';
import './index.less';
import {
    Form, Input,
} from 'antd';
import { UserOutlined, LockOutlined, ArrowRightOutlined, SafetyOutlined } from '@ant-design/icons';
import { useIntl } from 'react-intl';
import shareData from 'src/utils/shareData';
import { DV_STORAGE_LOGIN } from 'src/utils/constants';
import { useHistory } from 'react-router-dom';
import { SwitchLanguage } from '@/component';
import { $http } from '@/http';
import { useCommonActions } from '@/store';
import { useVerificationCode } from '@/hooks';

type TLoginValues = {
    username: string,
    password: string,
    verificationCode?: string,
}

const CAPTCHA_CODES = new Set([10020010, 10020007, 10020006]);
const LOCK_CODES = new Set([10020009]);

const Login = () => {
    const { setIsDetailPage } = useCommonActions();
    const [loading, setLoading] = useState(false);
    const [needCaptcha, setNeedCaptcha] = useState(false);
    const [form] = Form.useForm();
    const intl = useIntl();
    const history = useHistory();
    const { RenderImage, verificationCodeJwt } = useVerificationCode();

    const refreshAttemptStatus = async (username?: string) => {
        if (!username) {
            return;
        }
        try {
            const res = await $http.get('/login/attemptStatus', { username });
            if (res?.needCaptcha) {
                setNeedCaptcha(true);
            }
            if (res?.locked) {
                setNeedCaptcha(true);
            }
        } catch (e) {
            // ignore
        }
    };

    const onFinish = async () => {
        form.validateFields().then(async (values: TLoginValues) => {
            try {
                setLoading(true);
                const payload: any = {
                    username: values.username,
                    password: values.password,
                };
                if (needCaptcha) {
                    payload.verificationCode = values.verificationCode;
                    payload.verificationCodeJwt = verificationCodeJwt;
                }
                const res = await $http.post('/login', payload, { showWholeData: true });
                shareData.storageSet(DV_STORAGE_LOGIN, {
                    ...(res.data),
                    token: res.token,
                });
                setIsDetailPage(false);
                history.push('/main/home');
            } catch (error: any) {
                const code = error?.code;
                if (CAPTCHA_CODES.has(code) || LOCK_CODES.has(code) || code === 10020003) {
                    setNeedCaptcha(true);
                    await refreshAttemptStatus(values.username);
                }
            } finally {
                setLoading(false);
            }
        }).catch(() => {});
    };
    return (
        <div className="dv-login">
            <div className="dv-login__switch-language"><SwitchLanguage /></div>
            <div className="dv-login-containner">
                <div className="dv-login-wrap">
                    <div className="dv-login-title main-color">Datavines</div>
                    <Form
                        form={form}
                        layout="vertical"
                        name="dv-login"
                    >
                        <Form.Item
                            name="username"
                            style={{ marginBottom: 15 }}
                            rules={[{ required: true, message: intl.formatMessage({ id: 'login_username_msg' }) }]}
                        >
                            <Input
                                autoComplete="off"
                                style={{ height: 50 }}
                                size="large"
                                prefix={<UserOutlined />}
                                onBlur={(e) => refreshAttemptStatus(e.target.value)}
                            />
                        </Form.Item>

                        <Form.Item
                            name="password"
                            style={{ marginBottom: 15 }}
                            rules={[{ required: true, message: intl.formatMessage({ id: 'login_password_msg' }) }]}
                        >
                            <Input.Password style={{ height: 50 }} size="large" prefix={<LockOutlined />} />
                        </Form.Item>
                        {needCaptcha ? (
                            <Form.Item
                                name="verificationCode"
                                style={{ marginBottom: 15 }}
                                rules={[{ required: true, message: intl.formatMessage({ id: 'verification_code_text' }) }]}
                            >
                                <Input
                                    autoComplete="off"
                                    style={{ height: 50 }}
                                    size="large"
                                    prefix={<SafetyOutlined />}
                                    addonAfter={(
                                        <RenderImage
                                            style={{
                                                display: 'inline-block',
                                                height: '40px',
                                                margin: '0 -11px',
                                                cursor: 'pointer',
                                            }}
                                        />
                                    )}
                                />
                            </Form.Item>
                        ) : null}
                        <p className="dv-login-btn">
                            <span style={{ visibility: 'hidden' }}>.</span>
                            <span
                                onClick={() => {
                                    if (!loading) {
                                        onFinish();
                                    }
                                }}
                                style={{ opacity: loading ? 0.6 : 1 }}
                            >
                                {intl.formatMessage({ id: 'login_btn_text' })}
                                <ArrowRightOutlined />
                            </span>
                        </p>
                    </Form>
                </div>
            </div>
        </div>
    );
};
export default Login;
