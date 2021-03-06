package cn.hba.audit.flume.soc;

import cn.hba.audit.flume.interceptor.JsonEventConverter;
import cn.hba.audit.flume.soc.exception.abandon.AbandonConstant;
import cn.hba.audit.flume.soc.log.log360.SyslogParse360;
import cn.hba.audit.flume.soc.log.logahjh.SyslogParseAhjh;
import cn.hba.audit.flume.soc.log.logapt.SyslogParseApt;
import cn.hba.audit.flume.soc.log.logdp.SyslogParseDp;
import cn.hba.audit.flume.soc.log.logh3c.SyslogParseH3c;
import cn.hba.audit.flume.soc.log.loghw.SyslogParseHw;
import cn.hba.audit.flume.soc.log.logjs.SyslogParseJs;
import cn.hba.audit.flume.soc.log.logkb.SyslogParseKb;
import cn.hba.audit.flume.soc.log.loglm.SyslogParseLm;
import cn.hba.audit.flume.soc.log.logrs.SyslogParseRs;
import cn.hba.audit.flume.soc.log.logsfd.SyslogParseSfd;
import cn.hba.audit.flume.soc.log.logss.SyslogParseSs;
import cn.hba.audit.flume.soc.log.logsxf.SyslogParseSxf;
import cn.hba.audit.flume.soc.log.logtrx.SyslogParseTrx;
import cn.hba.audit.flume.soc.log.logwk.SyslogParseWk;
import cn.hba.audit.flume.soc.log.logws.SyslogParseWs;
import cn.hba.audit.flume.soc.log.logwyxy.SyslogParseWyxy;
import cn.hba.audit.flume.util.DaTiUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.Validator;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import org.apache.flume.Event;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 用以区分不同种类通道
 *
 * @author wbw
 * @date 2019/9/6 11:27
 */
public class SyslogParseChannels {

    private Log log = LogFactory.get(SyslogParseChannels.class);
    /**
     * 设备ip 对应 厂商名称
     */
    private Map<String, String> facilityIp = new HashMap<>(20);
    /**
     * 厂家名称
     */
    private Map<String, SyslogParse> keys = new HashMap<>(10);

    /**
     * syslog 信息种类处理
     *
     * @param headers 头部信息
     * @param body    内容
     * @return json 信息
     */
    private Object dispose(Map<String, String> headers, String body) {
        // 原始信息
        String ip = headers.get("facility_ip");
        if (!facilityIp.containsKey(ip)) {
            return null;
        }
        JSONObject objBody = JSONUtil.parseObj(body);
        JSONObject bodyObj = JSONUtil.createObj();
        bodyObj.put("syslog", objBody.getStr("syslog"));
        bodyObj.put("facility_ip",ip);
//        SyslogParse syslogParse = new SyslogParseApt();
        SyslogParse syslogParse = keys.get(facilityIp.get(ip));

        Object parse = syslogParse.parse(bodyObj.toString());
        if (AbandonConstant.ABANDON.equals(String.valueOf(parse))) {
            return parse;
        }
        JSONObject obj = JSONUtil.parseObj(parse);
        if (CollUtil.isEmpty(obj)) {
            return null;
        }
        if (!obj.containsKey("facility")) {
            obj.put("facility", objBody.getInt("Facility", 6));
        }
        if (!obj.containsKey("priority")) {
            obj.put("priority", objBody.getInt("Priority", 6));
        }
        obj.put("center_time", DateUtil.date().toString(DaTiUtil.FORMAT));
        if (!obj.containsKey("log_level")) {
            obj.put("log_level", objBody.getStr("Severity", "6"));
        }

        headers.put("topic", obj.getStr("log_type"));
        obj.put("log_type", (obj.getStr("log_type") + "_" + obj.getStr("event_type")).toLowerCase());
        if (!obj.containsKey("module_type")) {
            obj.put("module_type", "safe");
        }
        if (!obj.containsKey("system_type")) {
            obj.put("system_type", "system");
        }
        obj.put("syslog", objBody.getStr("syslog"));
        obj.put("facility_ip", ip);
        try {
            if (obj.containsKey("event_time")) {
                if (!obj.getStr("event_time").contains("+")) {
                    obj.put("event_time", DateUtil.parse(obj.getStr("event_time")).toString(DaTiUtil.FORMAT));
                }
            } else {
                obj.put("event_time", DaTiUtil.disEventTime(objBody.getStr("syslog")));
            }
        } catch (Exception e) {
            obj.put("event_time", DaTiUtil.disEventTime(objBody.getStr("syslog")));
        }
        headers.put("module_type", obj.getStr("module_type", "safe"));
        headers.put("system_type", obj.getStr("system_type", "system"));
        headers.put("log_type", obj.getStr("log_type").toLowerCase());
        headers.put("event_type", obj.getStr("event_type").toLowerCase());
        return obj;
    }

    /**
     * 加载设备地址配置
     *
     * @param ipConfig ip地址配置
     */
    void loadFacilityIp(String ipConfig) {
        if (StrUtil.isBlank(ipConfig) || !JSONUtil.isJsonObj(ipConfig)) {
            log.error("ipConfig is not configured correctly...");
            System.exit(0);
        }
        this.putKeyBySyslogParse();

        JSONObject obj = JSONUtil.parseObj(ipConfig);
        obj.keySet().forEach(key -> {
            if (!keys.containsKey(key)) {
                log.error("ipConfig is not configured correctly....");
                System.exit(0);
            }
            Arrays.asList(obj.getStr(key).split(",")).forEach(ip -> {
                if (!Validator.isIpv4(ip)) {
                    log.error("ipConfig is not configured correctly....");
                    System.exit(0);
                }
                if (!facilityIp.containsKey(ip)) {
                    facilityIp.put(ip, key);
                }
            });
        });
        log.info("ipConfig configuration:\t{}", facilityIp);
    }

    /**
     * 添加 接口
     */
    private void putKeyBySyslogParse() {
        // 360
        keys.put("log_360", new SyslogParse360());
        // APT
        keys.put("log_apt", new SyslogParseApt());
        // 迪普
        keys.put("log_dp", new SyslogParseDp());
        // HSC
        keys.put("log_h3c", new SyslogParseH3c());
        // 华为
        keys.put("log_hw", new SyslogParseHw());
        // 金山
        keys.put("log_js", new SyslogParseJs());
        // 科博
        keys.put("log_kb", new SyslogParseKb());
        // 绿盟
        keys.put("log_lm", new SyslogParseLm());
        // 瑞数
        keys.put("log_rs", new SyslogParseRs());
        // 思福迪
        keys.put("log_sfd", new SyslogParseSfd());
        // 山石
        keys.put("log_ss", new SyslogParseSs());
        // 深信服
        keys.put("log_sxf", new SyslogParseSxf());
        // 天融信
        keys.put("log_trx", new SyslogParseTrx());
        // 网康
        keys.put("log_wk", new SyslogParseWk());
        // 网神
        keys.put("log_ws", new SyslogParseWs());
        // 网御星云
        keys.put("log_wyxy", new SyslogParseWyxy());
        // 安华金和
        keys.put("log_ahjh", new SyslogParseAhjh());
    }

    /**
     * 采集日志，用ip以区分
     *
     * @param headers 头部
     * @param body    内容
     * @return Object
     */
    private Object collect(Map<String, String> headers, String body) {
        String facilityIp = headers.get("facility_ip");
        headers.put("center_time", DateUtil.now());
        headers.put("log_type", "collect");
        headers.put("event_type", facilityIp.replaceAll("\\.", "_"));

        JSONObject bodyObj = JSONUtil.parseObj(body);
        JSONObject obj = JSONUtil.createObj();
        obj.put("facility_ip", facilityIp);
        obj.put("center_time", DateUtil.now());
        if (!obj.containsKey("facility")) {
            obj.put("facility", bodyObj.getInt("Facility", 6));
        }
        obj.put("log_level", bodyObj.getInt("Severity", 6));
        obj.put("syslog", bodyObj.getStr("syslog"));
        if (!obj.containsKey("priority")) {
            obj.put("priority", bodyObj.getInt("Priority", 6));
        }
        return obj;
    }

    /**
     * 拦截器
     *
     * @param isGatherLog 是否收集日志，false不收集
     * @param event       event
     * @return Event
     */
    public Event intercept(boolean isGatherLog, Event event) {
        Map<String, String> headers = event.getHeaders();
        String eventBody = new String(event.getBody(), StandardCharsets.UTF_8);
        Object body;
        if (isGatherLog) {
            body = this.collect(headers, eventBody);
        } else {
            body = this.dispose(headers, eventBody);
        }
        Assert.isTrue(body != null, "Error events are discarded...");
        if (AbandonConstant.ABANDON.equals(body)) {
            Assert.isTrue(false, "Take the initiative to discard...");
        }
        body = this.delBlank(body);
        if (body instanceof String) {
            event.setBody((String.valueOf(body)).getBytes(StandardCharsets.UTF_8));
        } else {
            event.setBody(JsonEventConverter.get().convert(body));
        }
        log.debug("Syslog processing is complete:\t{}", body);
        return event;
    }

    /**
     * 去除空值与null,条件格式化数字
     */
    private Object delBlank(Object body) {
        JSONObject obj = JSONUtil.parseObj(body);
        JSONObject o = new JSONObject();
        obj.keySet().forEach(e -> {
            Object v = obj.getObj(e);
            String va = v.toString();
            if (NumberUtil.isNumber(va) && NUM_LIMIT_FIELDS.contains(e)) {
                o.put(e, NumberUtil.isLong(va) ? NumberUtil.parseLong(va) : NumberUtil.parseNumber(va).doubleValue());
            } else if (!StrUtil.isBlankIfStr(v)) {
                va = StrUtil.trim(v.toString());
                if (StrUtil.isNotBlank(va)) {
                    if (JSONUtil.isJsonArray(va)) {
                        va = va.substring(1, va.length() - 1);
                    }
                    o.put(e, va);
                }
            }
        });
        return o;
    }

    /**
     * 限制字段转换成数字
     */
    private static final List<String> NUM_LIMIT_FIELDS = numFiled();

    /**
     * 读取过滤字段
     *
     * @return List
     */
    public static List<String> numFiled() {
        LinkedList<String> list = CollUtil.newLinkedList();
        FileUtil.readLines(ResourceUtil.getResource("flume.number.field"), CharsetUtil.UTF_8).forEach(filed -> {
            if (!filed.contains("#")) {
                list.addAll(StrUtil.splitTrim(StrUtil.removeAllLineBreaks(StrUtil.trim(filed)), ","));
            }
        });
        return new ArrayList<>(CollUtil.distinct(list));
    }
}
