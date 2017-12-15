package com.achain.service.impl;


import com.achain.conf.Config;
import com.achain.domain.dto.TransactionDTO;
import com.achain.domain.enums.TrxType;
import com.achain.service.IBlockchainService;
import com.achain.utils.SDKHttpClient;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;

import lombok.extern.slf4j.Slf4j;

/**
 * @author fyk
 * @since 2017-11-29 19:13
 */
@Service
@Slf4j
public class BlockchainServiceImpl implements IBlockchainService {

    private final SDKHttpClient httpClient;

    private final Config config;

    @Autowired
    public BlockchainServiceImpl(SDKHttpClient httpClient, Config config) {
        this.httpClient = httpClient;
        this.config = config;
    }

    @Override
    public long getBlockCount() {
        log.info("BlockchainServiceImpl|getBlockCount 开始处理");
        String result =
            httpClient.post(config.walletUrl, config.rpcUser, "blockchain_get_block_count", new JSONArray());
        JSONObject createTaskJson = JSONObject.parseObject(result);
        return createTaskJson.getLong("result");
    }

    @Override
    public JSONArray getBlock(long blockNum) {
        log.info("BlockchainServiceImpl|getBlock 开始处理[{}]", blockNum);
        String result =
            httpClient.post(config.walletUrl, config.rpcUser, "blockchain_get_block", String.valueOf(blockNum));
        JSONObject createTaskJson = JSONObject.parseObject(result);
        return createTaskJson.getJSONObject("result").getJSONArray("user_transaction_ids");
    }


    /**
     * 需要判断交易类型，合约id，合约调用的方法和转账到的地址。
     *
     * @param trxId 交易单号
     */
    @Override
    public TransactionDTO getTransaction(long blockNum, String trxId) {
        try {
            log.info("BlockchainServiceImpl|getBlock 开始处理[{}]", trxId);
            String result = httpClient.post(config.walletUrl, config.rpcUser, "blockchain_get_transaction", trxId);
            JSONObject createTaskJson = JSONObject.parseObject(result);
            JSONArray resultJsonArray = createTaskJson.getJSONArray("result");
            JSONObject operationJson = resultJsonArray.getJSONObject(1)
                                                      .getJSONObject("trx")
                                                      .getJSONArray("operations")
                                                      .getJSONObject(0);
            //判断交易类型
            String operationType = operationJson.getString("type");
            //不是合约调用就忽略
            if (!"transaction_op_type".equals(operationType)) {
                return null;
            }

            JSONObject operationData = operationJson.getJSONObject("data");
            log.info("BlockchainServiceImpl|operationData={}", operationData);

            String resultTrxId =
                resultJsonArray.getJSONObject(1).getJSONObject("trx").getString("result_trx_id");
            JSONArray jsonArray = new JSONArray();
            jsonArray.add(StringUtils.isEmpty(resultTrxId) ? trxId : resultTrxId);
            log.info("getTransaction|transaction_op_type|[blockId={}][trxId={}][result_trx_id={}]", blockNum, trxId,
                     resultTrxId);
            String resultSignee =
                httpClient.post(config.walletUrl, config.rpcUser, "blockchain_get_pretty_contract_transaction", jsonArray);
            JSONObject resultJson2 = JSONObject.parseObject(resultSignee).getJSONObject("result");
            //和广播返回的统一
            String origTrxId = resultJson2.getString("orig_trx_id");
            Integer trxType = Integer.parseInt(resultJson2.getString("trx_type"));

            Date trxTime = dealTime(resultJson2.getString("timestamp"));
            JSONArray reserved = resultJson2.getJSONArray("reserved");
            JSONObject temp = resultJson2.getJSONObject("to_contract_ledger_entry");
            String contractId = temp.getString("to_account");
            //不是游戏的合约id就忽略
            if (!config.contractId.equals(contractId)) {
                return null;
            }
            TrxType type = TrxType.getTrxType(trxType);
            if (TrxType.TRX_TYPE_DEPOSIT_CONTRACT == type) {
                TransactionDTO transactionDTO = new TransactionDTO();
                transactionDTO.setTrxId(origTrxId);
                transactionDTO.setBlockNum(blockNum);
                transactionDTO.setTrxTime(trxTime);
                transactionDTO.setContractId(contractId);
                //transactionDTO.setCallAbi(ContractGameMethod.RECHARGE.getValue());
                return transactionDTO;
            } else if (TrxType.TRX_TYPE_CALL_CONTRACT == type) {
                String fromAddr = temp.getString("from_account");
                Long amount = temp.getJSONObject("amount").getLong("amount");
                String callAbi = reserved.size() >= 1 ? reserved.getString(0) : null;
                String apiParams = reserved.size() > 1 ? reserved.getString(1) : null;
                //没有方法名
                if (StringUtils.isEmpty(callAbi)) {
                    return null;
                }
                jsonArray = new JSONArray();
                jsonArray.add(blockNum);
                jsonArray.add(trxId);
                String data = httpClient.post(config.walletUrl, config.rpcUser, "blockchain_get_events", jsonArray);
                JSONObject jsonObject = JSONObject.parseObject(data);
                JSONArray jsonArray1 = jsonObject.getJSONArray("result");
                JSONObject resultJson = new JSONObject();
                parseEventData(resultJson, jsonArray1);
                TransactionDTO transactionDTO = new TransactionDTO();
                transactionDTO.setContractId(contractId);
                transactionDTO.setTrxId(origTrxId);
                transactionDTO.setEventParam(resultJson.getString("event_param"));
                transactionDTO.setEventType(resultJson.getString("event_type"));
                transactionDTO.setBlockNum(blockNum);
                transactionDTO.setTrxTime(trxTime);
                transactionDTO.setCallAbi(callAbi);
                transactionDTO.setFromAddr(fromAddr);
                transactionDTO.setAmount(amount);
                transactionDTO.setApiParams(apiParams);
                return transactionDTO;
            }
        } catch (Exception e) {
            log.error("BlockchainServiceImpl", e);
        }
        return null;
    }



    private void parseEventData(JSONObject result, JSONArray jsonArray1) {
        if (Objects.nonNull(jsonArray1) && jsonArray1.size() > 0) {
            StringBuffer eventType = new StringBuffer();
            StringBuffer eventParam = new StringBuffer();
            jsonArray1.forEach(json -> {
                JSONObject jso = (JSONObject) json;
                eventType.append(eventType.length() > 0 ? "|" : "").append(jso.getString("event_type"));
                eventParam.append(eventParam.length() > 0 ? "|" : "").append(jso.getString("event_param"));
            });
            result.put("event_type", eventType);
            result.put("event_param", eventParam);
        }
    }

    private Date dealTime(String timestamp) {
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            return format.parse(timestamp);
        } catch (ParseException e) {
            log.error("dealTime|error|", e);
            return null;
        }
    }



}
