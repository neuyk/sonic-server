/*
 *  Copyright (C) [SonicCloudOrg] Sonic Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.cloud.sonic.controller.services.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.dubbo.config.annotation.DubboService;
import org.cloud.sonic.controller.mapper.*;
import org.cloud.sonic.common.models.domain.*;
import org.cloud.sonic.common.models.dto.StepsDTO;
import org.cloud.sonic.common.services.GlobalParamsService;
import org.cloud.sonic.common.services.StepsService;
import org.cloud.sonic.common.services.TestCasesService;
import org.cloud.sonic.common.services.TestSuitesService;
import org.cloud.sonic.controller.services.impl.base.SonicServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author ZhouYiXun
 * @des 测试用例逻辑实现
 * @date 2021/8/20 17:51
 */
@Service
@DubboService
public class TestCasesServiceImpl extends SonicServiceImpl<TestCasesMapper, TestCases> implements TestCasesService {

    @Autowired private StepsService stepsService;
    @Autowired private GlobalParamsService globalParamsService;
    @Autowired private TestSuitesTestCasesMapper testSuitesTestCasesMapper;
    @Autowired private TestSuitesService testSuitesService;
    @Autowired private TestCasesMapper testCasesMapper;
    @Autowired private StepsMapper stepsMapper;
    @Autowired private StepsElementsMapper stepsElementsMapper;

    @Override
    public Page<TestCases> findAll(int projectId, int platform, String name, Page<TestCases> pageable) {

        LambdaQueryChainWrapper<TestCases> lambdaQuery = lambdaQuery();
        if (projectId != 0) {
            lambdaQuery.eq(TestCases::getProjectId, projectId);
        }
        if (platform != 0) {
            lambdaQuery.eq(TestCases::getPlatform, platform);
        }
        if (name != null && name.length() > 0) {
            lambdaQuery.like(TestCases::getName, name);
        }

        return lambdaQuery.orderByDesc(TestCases::getEditTime)
                .page(pageable);
    }

    @Override
    public List<TestCases> findAll(int projectId, int platform) {
        return lambdaQuery().eq(TestCases::getProjectId, projectId)
                .eq(TestCases::getPlatform, platform)
                .orderByDesc(TestCases::getEditTime)
                .list();
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean delete(int id) {
        if (existsById(id)) {
            // 删除suite映射关系
            testSuitesTestCasesMapper.delete(
                    new LambdaQueryWrapper<TestSuitesTestCases>()
                            .eq(TestSuitesTestCases::getTestCasesId, id)
            );

            List<StepsDTO> stepsList = stepsService.findByCaseIdOrderBySort(id);
            for (StepsDTO steps : stepsList) {
                steps.setCaseId(0);
                stepsService.updateById(steps.convertTo());
            }
            baseMapper.deleteById(id);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public TestCases findById(int id) {
        return baseMapper.selectById(id);
    }

    @Transactional
    @Override
    public JSONObject findSteps(int id) {

        if (existsById(id)) {
            TestCases runStepCase = baseMapper.selectById(id);
            JSONObject jsonDebug = new JSONObject();
            jsonDebug.put("pf", runStepCase.getPlatform());

            JSONArray array = new JSONArray();
            List<StepsDTO> stepsList = stepsService.findByCaseIdOrderBySort(id);
            for (StepsDTO steps : stepsList) {
                array.add(testSuitesService.getStep(steps));
            }
            jsonDebug.put("steps", array);
            List<GlobalParams> globalParamsList = globalParamsService.findAll(runStepCase.getProjectId());
            JSONObject gp = new JSONObject();
            Map<String, List<String>> valueMap = new HashMap<>();
            for (GlobalParams g : globalParamsList) {
                if (g.getParamsValue().contains("|")) {
                    List<String> shuffle = Arrays.asList(g.getParamsValue().split("\\|"));
                    Collections.shuffle(shuffle);
                    valueMap.put(g.getParamsKey(), shuffle);
                } else {
                    gp.put(g.getParamsKey(), g.getParamsValue());
                }
            }
            for (String k : valueMap.keySet()) {
                if (valueMap.get(k).size() > 0) {
                    String v = valueMap.get(k).get(0);
                    gp.put(k, v);
                }
            }
            jsonDebug.put("gp", gp);
            return jsonDebug;
        } else {
            return null;
        }
    }

    @Override
    public List<TestCases> findByIdIn(List<Integer> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return new ArrayList<>();
        }
        return listByIds(ids);
    }

    @Override
    public boolean deleteByProjectId(int projectId) {
        return baseMapper.delete(new LambdaQueryWrapper<TestCases>().eq(TestCases::getProjectId, projectId)) > 0;
    }

    @Override
    public List<TestCases> listByPublicStepsId(int publicStepsId) {
        List<Steps> steps = stepsService.lambdaQuery().eq(Steps::getText, publicStepsId).list();
        if (CollectionUtils.isEmpty(steps)) {
            return new ArrayList<>();
        }
        Set<Integer> caseIdSet = steps.stream().map(Steps::getCaseId).collect(Collectors.toSet());
        return lambdaQuery().in(TestCases::getId, caseIdSet).list();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public  boolean copyTestById(int oldId) {
        TestCases testCase = testCasesMapper.selectById(oldId);
        testCase.setId(null).setEditTime(null).setName(testCase.getName()+"_copy");
        save(testCase);

        LambdaQueryWrapper<Steps> lqw = new LambdaQueryWrapper<>();
        List<Steps> steps = stepsMapper.selectList(lqw.eq(Steps::getCaseId, oldId));
        List<Steps> stepsNeedEleList = stepsMapper.selectList(lqw.eq(Steps::getContent, ""));

        for (Steps step : steps) {
            step.setId(null).setCaseId(testCase.getId());
            stepsMapper.insert(step);
        }
        //steps_elements 表没有需要关联的数据就直接返回成功
        if (stepsNeedEleList == null || stepsNeedEleList.size() == 0) {
            return  true;
        }

        List<StepsElements> stepsElements = new ArrayList<>();
        for (Steps value : stepsNeedEleList) {
            LambdaQueryWrapper<StepsElements> lm = new LambdaQueryWrapper<>();
            List<StepsElements> st = stepsElementsMapper.selectList(lm.eq(StepsElements::getStepsId, value.getId()));
            stepsElements.add(st.get(0));
        }

        LambdaQueryWrapper<Steps> lqwNewCaseId = new LambdaQueryWrapper<>();
        List<Steps> newStepsEleId =stepsMapper.selectList(lqwNewCaseId
                            .eq(Steps::getContent, "")
                            .eq(Steps::getCaseId,testCase.getId()));
        for (int i = 0; i < newStepsEleId.size(); i++) {
            stepsElementsMapper.insert(
                    new StepsElements()
                            .setStepsId(newStepsEleId.get(i).getId())
                            .setElementsId(stepsElements.get(i).getElementsId()));
        }
        return true;
    }
}
