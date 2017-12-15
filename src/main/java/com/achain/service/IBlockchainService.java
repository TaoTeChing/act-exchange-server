package com.achain.service;


import com.achain.domain.dto.TransactionDTO;
import com.alibaba.fastjson.JSONArray;

/**
 * @author fyk
 * @since 2017-11-29 19:09
 */
public interface IBlockchainService {

    /**
     * 获取当前最新区块
     *
     * @return 最新区块号
     */

    long getBlockCount();

    /**
     * 获取一个区块的数据信息
     *
     * @param blockNum 区块号
     * @return 这个区块上的交易
     */

    JSONArray getBlock(long blockNum);

    /**
     * 根据交易单号获取给指定地址打SMC币的地址
     *
     * @param trxId 交易单号
     * @return Map中包含两个参数，from是打币者的地址，amount是打币者的打币数量
     */
    TransactionDTO getTransaction(long blockNum, String trxId);


}
