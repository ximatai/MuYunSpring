export const WELINK_ORIGIN = 'https://login.welink.huaweicloud.com';
export const WELINK_SCRIPT = `${WELINK_ORIGIN}/sso-proxy-front/public/qrcode/0.0.1/wlQrcodeLogin.js`;

type CreateQrCode = (options: {
  id: string;
  client_id: string;
  redirect_uri: string;
  state: string;
  scope: string;
  response_type: string;
  self_redirect: string;
  lang: string;
  width: number;
  height: number;
}) => void;

declare global {
  interface Window {
    wlQrcodeLogin?: CreateQrCode;
  }
}

let loading: Promise<CreateQrCode> | undefined;

/** One official script per document; failed loads can be retried by a fresh transaction. */
export function loadWeLinkScript(): Promise<CreateQrCode> {
  if (window.wlQrcodeLogin) return Promise.resolve(window.wlQrcodeLogin);
  if (loading) return loading;
  loading = new Promise<CreateQrCode>((resolve, reject) => {
    const script = document.createElement('script');
    script.src = WELINK_SCRIPT;
    script.async = true;
    script.referrerPolicy = 'no-referrer';
    const timer = setTimeout(fail, 15000);
    function fail() {
      clearTimeout(timer);
      script.remove();
      loading = undefined;
      reject(new Error('WeLink script unavailable'));
    }
    script.onerror = fail;
    script.onload = () => {
      if (!window.wlQrcodeLogin) return fail();
      clearTimeout(timer);
      resolve(window.wlQrcodeLogin);
    };
    document.head.append(script);
  });
  return loading;
}
