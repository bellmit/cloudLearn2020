package com.ruigu.rbox.workflow.service.impl;

import com.UpYun;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.ruigu.rbox.cloud.kanai.util.ConvertUtil;
import com.ruigu.rbox.cloud.kanai.web.page.PageImpl;
import com.ruigu.rbox.workflow.config.ReportEmailTargetParamProperties;
import com.ruigu.rbox.workflow.config.UpyunConfig;
import com.ruigu.rbox.workflow.exceptions.VerificationFailedException;
import com.ruigu.rbox.workflow.feign.PassportFeignClient;
import com.ruigu.rbox.workflow.model.ServerResponse;
import com.ruigu.rbox.workflow.model.dto.DepartmentIssueDTO;
import com.ruigu.rbox.workflow.model.dto.LightningIssueReportDTO;
import com.ruigu.rbox.workflow.model.dto.LightningIssueReportNewDTO;
import com.ruigu.rbox.workflow.model.dto.WholeReportDTO;
import com.ruigu.rbox.workflow.model.enums.QueryFormType;
import com.ruigu.rbox.workflow.model.enums.ResponseCode;
import com.ruigu.rbox.workflow.model.vo.EmailAttachment;
import com.ruigu.rbox.workflow.model.vo.GroupAndUserVO;
import com.ruigu.rbox.workflow.model.vo.lightning.DepartmentIssueVO;
import com.ruigu.rbox.workflow.model.vo.lightning.LightningIssueReportNewVO;
import com.ruigu.rbox.workflow.model.vo.lightning.LightningIssueReportVO;
import com.ruigu.rbox.workflow.model.vo.lightning.UserDepartmentsAndNameVO;
import com.ruigu.rbox.workflow.repository.LightningIssueApplyRepository;
import com.ruigu.rbox.workflow.repository.LightningIssueLogRepository;
import com.ruigu.rbox.workflow.service.LightningIssueReportService;
import com.ruigu.rbox.workflow.service.QuestNoticeService;
import com.upyun.UpException;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author caojinghong
 * @date 2020/01/09 15:15
 */
@Service
@Slf4j
public class LightningIssueReportServiceImpl implements LightningIssueReportService {
    @Autowired
    private LightningIssueLogRepository logRepository;
    @Autowired
    private UpyunConfig upyunConfig;
    @Autowired
    private QuestNoticeService questNoticeService;
    @Autowired
    private PassportFeignClient passportFeignClient;
    @Autowired
    private LightningIssueApplyRepository applyRepository;

    @Autowired
    private ReportEmailTargetParamProperties reportEmailTargetParamProperties;

    @Override
    public ServerResponse exportExcel(Integer queryType) throws Exception {
        LocalDate queryMonth;
        if (QueryFormType.LAST_MONTH.getCode().equals(queryType)) {
            // ?????????????????????
            queryMonth = LocalDate.now().minusMonths(1);
        } else if (QueryFormType.CURRENT_MONTH.getCode().equals(queryType)) {
            queryMonth = LocalDate.now();
        } else {
            throw new VerificationFailedException(ResponseCode.REQUEST_ERROR.getCode(), "queryType??????");
        }
        int year = queryMonth.getYear();
        int monthValue = queryMonth.getMonthValue();
        LocalDateTime start = LocalDateTime.of(queryMonth.with(TemporalAdjusters.firstDayOfMonth()), LocalTime.MIN);
        LocalDateTime end = LocalDateTime.of(queryMonth.with(TemporalAdjusters.lastDayOfMonth()), LocalTime.MAX);
        Date startTime = Date.from( start.atZone( ZoneId.systemDefault()).toInstant());
        Date endTime = Date.from( end.atZone( ZoneId.systemDefault()).toInstant());

        String name = "???????????????????????????-" + year +"???"+ monthValue +"???-";
        File tempFile = File.createTempFile(name, ".xlsx");

        ExcelWriter excelWriter = EasyExcel.write(tempFile).build();
        WriteSheet writeSheet1 = EasyExcel.writerSheet(0,"??????").head(WholeReportDTO.class).build();
        WriteSheet writeSheet2 = EasyExcel.writerSheet(1, "??????").head(LightningIssueReportVO.class).build();
        WriteSheet writeSheet3 = EasyExcel.writerSheet(2,"?????????").head(DepartmentIssueVO.class).build();

        Pageable page = PageRequest.of(0,Integer.MAX_VALUE);
        // ?????????????????????
        Page<Map> wholeMaps= applyRepository.queryWholeData(startTime, endTime, page);
        List<WholeReportDTO> sheet1Data = PageImpl.of(wholeMaps, x -> ConvertUtil.mapToObject(x, WholeReportDTO.class)).getContent();
        WholeReportDTO wholeReportDto = sheet1Data.get(0);
        if (wholeReportDto.getIssueCount().equals(0)) {
            wholeReportDto.setIssueDemandCount(0);
            wholeReportDto.setEvaluateScoreCount(0);
            excelWriter.write(sheet1Data, writeSheet1);
            excelWriter.finish();
            String upYunFilePath = uploadFile(tempFile, name);
            return ServerResponse.ok("???????????????????????????????????????????????? "+upYunFilePath);
        }
        excelWriter.write(sheet1Data, writeSheet1);

        // ?????????????????????
        Page<Map> maps = logRepository.queryReportForm(startTime, endTime, page);
        List<LightningIssueReportDTO> reportDtos = PageImpl.of(maps, x -> ConvertUtil.mapToObject(x, LightningIssueReportDTO.class)).getContent();

        // ????????????????????????
        if (CollectionUtils.isNotEmpty(reportDtos)) {
            List<Integer> userIds = new ArrayList<>();
            for (LightningIssueReportDTO everyData: reportDtos) {
                userIds.add(everyData.getUserId());
            }
            ServerResponse<List<UserDepartmentsAndNameVO>> listServerResponse = passportFeignClient.queryUserDepartmentsAndName(userIds);
            List<UserDepartmentsAndNameVO> userList = listServerResponse.getData();
            if (listServerResponse.getCode() != ResponseCode.SUCCESS.getCode() || CollectionUtils.isEmpty(userList)) {
                throw new VerificationFailedException(ResponseCode.INTERNAL_ERROR.getCode(), "??????????????????????????????????????????????????????");
            }
            Map<Integer, UserDepartmentsAndNameVO> collect = handleListToMap(userList);
            List<LightningIssueReportVO> sheet2Data = new ArrayList<>();
            for (LightningIssueReportDTO everyData: reportDtos) {
                Integer userId = everyData.getUserId();
                UserDepartmentsAndNameVO userDepartmentsAndNameVO = collect.get(userId);
                if (userDepartmentsAndNameVO == null) {
                    log.error("?????? ????????????????????????????????????????????????????????????????????????id: {}",userId);
                    continue;
                }
                LightningIssueReportVO reportVO = new LightningIssueReportVO();
                BeanUtils.copyProperties(everyData, reportVO);
                reportVO.setPersonName(userDepartmentsAndNameVO.getNickname());
                reportVO.setFirstLevelDepartmentName(userDepartmentsAndNameVO.getFirstLevelDepartment());
                reportVO.setSecondLevelDepartmentName(userDepartmentsAndNameVO.getSecondaryLevelDepartment());
                if (StringUtils.isBlank(reportVO.getSecondLevelDepartmentName())) {
                    reportVO.setSecondLevelDepartmentName("???");
                }
                sheet2Data.add(reportVO);
            }
            List<LightningIssueReportVO> returnData= sheet2Data.stream().sorted(Comparator.comparing(LightningIssueReportVO::getFirstLevelDepartmentName).thenComparing(LightningIssueReportVO::getSecondLevelDepartmentName)).collect(Collectors.toList());
            excelWriter.write(returnData, writeSheet2);

        }
        // ??????????????????????????????
        Page<Map> departmentMaps = applyRepository.queryDepartmentData(startTime, endTime, page);
        List<DepartmentIssueDTO> departmentContent = PageImpl.of(departmentMaps, x -> ConvertUtil.mapToObject(x, DepartmentIssueDTO.class)).getContent();
        Map<String, GroupInfo> groupAndUserInfo = getGroupAndUserInfo();
        if (CollectionUtils.isNotEmpty(departmentContent) && groupAndUserInfo != null) {

            List<DepartmentIssueVO> sheet3Data = new ArrayList<>();
            for (DepartmentIssueDTO departmentIssueDTO : departmentContent) {
                GroupInfo groupInfo = groupAndUserInfo.get(String.valueOf(departmentIssueDTO.getSecondLevelDepartmentId()));
                DepartmentIssueVO departmentIssueVO = new DepartmentIssueVO();
                BeanUtils.copyProperties(departmentIssueDTO, departmentIssueVO);
                departmentIssueVO.setFirstLevelDepartmentName(groupInfo.getFirstLevelGroup());
                departmentIssueVO.setSecondLevelDepartmentName(groupInfo.getSecondLevelGroup());
                sheet3Data.add(departmentIssueVO);
            }
            excelWriter.write(sheet3Data, writeSheet3);
        }

        // ???????????????finish ??????????????????
        excelWriter.finish();
        // ????????????????????????
        String upYunFilePath = uploadFile(tempFile, name);

        // ????????????
        tempFile.delete();

        // ????????????
        List<Integer> targetIds = reportEmailTargetParamProperties.getTargetList();
        List<Integer> ccTargetIds = reportEmailTargetParamProperties.getCcTargetList();
        Set<EmailAttachment> emailAttachments = new HashSet<>();
        EmailAttachment emailAttachment = new EmailAttachment();
        emailAttachment.setName(name+".xlsx");
        emailAttachment.setUrl(upYunFilePath);
        emailAttachments.add(emailAttachment);
        String title = "???????????????????????????"+ year +"???"+ monthValue +"???";
        String content = "????????????!";
        return questNoticeService.sendEmailNotice(null, title, content, targetIds, ccTargetIds, emailAttachments);
    }

    /**
     * ????????????
     * @param filePrefix ??????????????????????????????????????????properties????????????
     * @param fileName ????????????
     * @return ???????????????????????????
     */
    private String getFilePath(String filePrefix,String fileName) {
        return filePrefix + fileName + UUID.randomUUID().toString()+".xlsx";
    }

    /**
     * ????????????????????????
     * @param tempFile ????????????
     * @param fileName ????????????
     * @return ????????????????????????????????????????????????????????????
     * @throws IOException IO??????
     * @throws UpException ???????????????
     */
    private String uploadFile(File tempFile, String fileName) throws IOException, UpException {
        log.info("????????????????????????");
        String filePath =  getFilePath(upyunConfig.getFilePathPrefix(),fileName);
        UpYun upyun = new UpYun(upyunConfig.getBucketName(), upyunConfig.getUsername(), upyunConfig.getPassword());
        boolean isSuccess = upyun.writeFile(filePath, tempFile, true);
        if (!isSuccess) {
            throw new UpException("????????????????????????");
        }
        log.info("????????????????????????");
        return upyunConfig.getPrefix() + filePath;
    }

    /**
     * ??????passport??????????????????????????????????????????
     * @return ??????map???????????????????????????Id,?????????????????????????????????
     */
    private Map<String, GroupInfo> getGroupAndUserInfo(){
        ServerResponse<List<GroupAndUserVO>> groupAndUserInfoResponse = passportFeignClient.getGroupAndUserInfo(null, null);
        if (groupAndUserInfoResponse.getCode() != ResponseCode.SUCCESS.getCode()) {
            log.error("??????????????????????????????????????????");
            return null;
        }
        List<GroupAndUserVO> groupAndUserInfo = groupAndUserInfoResponse.getData();
        // ????????????id map
        Map<String, GroupInfo> map = new HashMap<>(32);
        for (GroupAndUserVO info : groupAndUserInfo) {
            if (CollectionUtils.isEmpty(info.getChildren())) {
                continue;
            }
            for (GroupAndUserVO subInfo : info.getChildren()) {
                if (subInfo.getValue() != null) {
                    GroupInfo groupInfo = new GroupInfo();
                    groupInfo.setFirstLevelGroup(info.getLabel());
                    groupInfo.setSecondLevelGroup(subInfo.getLabel());
                    map.put(subInfo.getValue().toString(), groupInfo);
                }
            }
        }
        return map;
    }

    /**
     * ??????????????????????????????????????????????????????????????????????????????????????????map??????????????????
     * @param userList ??????????????????
     * @return ???????????????Map
     */
    private Map<Integer, UserDepartmentsAndNameVO> handleListToMap(List<UserDepartmentsAndNameVO> userList){
        return userList.stream().collect(Collectors.toMap(
                UserDepartmentsAndNameVO::getUserId, Function.identity(),
                (user1, user2) -> {
                    String firstLevelDepartment1 = user1.getFirstLevelDepartment();
                    String firstLevelDepartment2 = user2.getFirstLevelDepartment();
                    String secondaryLevelDepartment1 = user1.getSecondaryLevelDepartment();
                    String secondaryLevelDepartment2 = user2.getSecondaryLevelDepartment();
                    String second = "/";
                    if (StringUtils.isBlank(secondaryLevelDepartment1)) {
                        user1.setSecondaryLevelDepartment("");
                        second = "";
                    }
                    if (StringUtils.isBlank(secondaryLevelDepartment2)) {
                        user2.setSecondaryLevelDepartment("");
                        second = "";
                    }
                    String secondName = user1.getSecondaryLevelDepartment() + second + user2.getSecondaryLevelDepartment();
                    user1.setSecondaryLevelDepartment(secondName);

                    String first = "/";
                    if (StringUtils.isEmpty(firstLevelDepartment1)) {
                        user1.setFirstLevelDepartment("");
                        first = "";
                    }
                    if (StringUtils.isEmpty(firstLevelDepartment2)) {
                        user2.setFirstLevelDepartment("");
                        first = "";
                    }
                    String firstName = user1.getFirstLevelDepartment() + first + user2.getFirstLevelDepartment();
                    user1.setFirstLevelDepartment(firstName);
                    return user1;
                }));
    }

    @Data
    private static class GroupInfo {
        private String firstLevelGroup;
        private String secondLevelGroup;
    }
}
