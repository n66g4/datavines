import React, { useEffect } from 'react';
import { useHistory } from 'react-router-dom';
import { Result } from 'antd';
import { useIntl } from 'react-intl';

/** Public registration is closed; redirect users to login. */
const Index = () => {
    const history = useHistory();
    const intl = useIntl();
    useEffect(() => {
        const t = setTimeout(() => history.replace('/login'), 1500);
        return () => clearTimeout(t);
    }, [history]);
    return (
        <div style={{ paddingTop: 120 }}>
            <Result
                status="info"
                title={intl.formatMessage({ id: 'register_closed_title' })}
                subTitle={intl.formatMessage({ id: 'register_closed_tip' })}
            />
        </div>
    );
};

export default Index;
