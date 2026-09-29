import http from 'k6/http';
import { check } from 'k6';

export const options = {
  vus: 200,
  duration: '60s',
};

export default function () {
  const res = http.get('http://localhost:8080/payments/settlement?merchantId=MR-4471');
  check(res, { 'status is 200': (r) => r.status === 200 });
}
