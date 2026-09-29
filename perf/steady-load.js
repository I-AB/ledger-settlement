import http from 'k6/http';
import { check } from 'k6';

export const options = {
    scenarios: {
        steady_load: {
            executor: 'constant-arrival-rate',
            rate: 50,
            timeUnit: '1s',
            duration: '10m',
            preAllocatedVUs: 20,
            maxVUs: 100,
        },
    },
};

export default function () {
    const res = http.get('http://localhost:8080/payments/settlement?merchantId=MR-4471');
    check(res, { 'status is 200': (r) => r.status === 200 });
}