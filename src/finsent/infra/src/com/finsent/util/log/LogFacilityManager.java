/*
 * Copyright (c) 1997-2000 InfoReach, Inc. All Rights Reserved.
 *
 * This software is the confidential and proprietary information of
 * InfoReach ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with InfoReach.
 *
 * CopyrightVersion 2.0
 *
 */

package com.finsent.util.log;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.finsent.util.GlobalDefs;
import com.finsent.util.GlobalSystem;
import com.finsent.util.xml.XMLData;

/**
 * A log facility manager that initializes and manages log facilities.
 *
 * <p>Facilities are created lazily by name from the process configuration (see
 * {@link GlobalSystem#getConfigData()}): the {@code OutputLogFacilityList} may
 * declare one {@code OutputLogFacility} element per facility name. A name with no
 * matching element resolves to the default (console) facility. This is a trimmed
 * port of the InfoReach {@code LogFacilityManager} (no log4j2 backend), keeping
 * the queue watermark setup and common-config propagation of the original.
 *
 * @author Oleg Minukhin
 */
public class LogFacilityManager
{
    private static final String QUEUE_SIZE_LOW_WATERMARK_ATTR = "queueSizeLowWatermark";
    private static final String QUEUE_SIZE_HI_WATERMARK_ATTR = "queueSizeHiWatermark";
    static final String LOGS_ROLLING_TIME_ATTR = "rollingTime";
    static final String LOGS_ROLLING_SIZE_ATTR = "rollingSize";
    static final String LOGS_ROLLOUT_ON_START_ATTR = "rolloutOnStart";
    static final String TIME_LAPSE_ATTR = "timeLapse";
    private static final int DEFAULT_HI_WATERMARK_VALUE = 20000;

    private static final Map<String, ILogFacility> LogFacilityMap_ = new HashMap<String, ILogFacility>();
    private static ILogFacility DefaultLogFacility_;

    private static boolean Initialized_;
    private static boolean SuppressLoggingAtShutdown_;

    /**
     * Returns a log facility by name, creating it from configuration on first
     * request (or the default facility when the name is not configured).
     *
     * @param name a log facility name.
     * @return a log facility.
     */
    public static ILogFacility getLogFacility(String name)
    {
        ILogFacility logFacility = LogFacilityMap_.get(name);
        if (logFacility == null)
        {
            initializeGlobalSettingsImpl();
            XMLData configData = findFacilityConfig(name);
            if (configData == null)
            {
                logFacility = getDefaultLogFacility();
            }
            else
            {
                logFacility = createLogFacility(name, configData);
                LogFacilityMap_.put(name, logFacility);
            }
        }
        return logFacility;
    }

    private static XMLData findFacilityConfig(String name)
    {
        XMLData result = null;
        XMLData globalData = GlobalSystem.getConfigData();
        XMLData facilityList = (globalData == null)
            ? null
            : globalData.getDocumentPart(GlobalDefs.CFG_OUTPUT_LOG_FACILITY_LIST, true);
        if (facilityList != null)
        {
            for (XMLData facilityData : facilityList.getChildrenByTagName(GlobalDefs.CFG_OUTPUT_LOG_FACILITY))
            {
                if (name != null && name.equals(facilityData.getAttributeStringValue(GlobalDefs.CFG_NAME, null)))
                {
                    result = facilityData;
                }
            }
        }
        return result;
    }

    public static ILogFacility createLogFacility(String name, XMLData configData)
    {
        return new LogFacility(configData, SuppressLoggingAtShutdown_);
    }

    /**
     * Returns the default (console) log facility.
     *
     * @return a log facility.
     */
    public static ILogFacility getDefaultLogFacility()
    {
        if (DefaultLogFacility_ == null)
        {
            initializeGlobalSettingsImpl();
            DefaultLogFacility_ = createLogFacility(null, null);
        }
        return DefaultLogFacility_;
    }

    public static void setDefaultLogFacility(ILogFacility defaultLogFacility)
    {
        DefaultLogFacility_ = defaultLogFacility;
    }

    /**
     * @return Iterator of ILogFacility.
     * @author Alexey Getmanchuk
     */
    public static Iterator<ILogFacility> iterator()
    {
        return LogFacilityMap_.values().iterator();
    }

    public static void initializeGlobalSettings()
    {
        Initialized_ = false;
        initializeGlobalSettingsImpl();
    }

    private static void initializeGlobalSettingsImpl()
    {
        if (!Initialized_)
        {
            Initialized_ = true;
            XMLData cfgData = GlobalSystem.getConfigData();
            if (cfgData != null)
            {
                int lowWatermark = DEFAULT_HI_WATERMARK_VALUE / 5;
                int hiWatermark = DEFAULT_HI_WATERMARK_VALUE;
                cfgData = cfgData.getDocumentPart(GlobalDefs.CFG_OUTPUT_LOG_FACILITY_LIST, false);
                if (cfgData != null)
                {
                    hiWatermark = cfgData.getAttributeIntValue(QUEUE_SIZE_HI_WATERMARK_ATTR, DEFAULT_HI_WATERMARK_VALUE);
                    lowWatermark = cfgData.getAttributeIntValue(QUEUE_SIZE_LOW_WATERMARK_ATTR, hiWatermark / 5);
                    SuppressLoggingAtShutdown_ = cfgData.getAttributeBooleanValue(GlobalDefs.SUPPRESS_LOGGING_AT_SHUTDOWN, false);
                    for (XMLData facilityData : cfgData.getChildrenByTagName(GlobalDefs.CFG_OUTPUT_LOG_FACILITY))
                    {
                        propagateCommonConfig(cfgData, facilityData);
                    }
                }
                Logger.setQueueWatermarks(lowWatermark, hiWatermark);
            }
        }
    }

    static void propagateCommonConfig(XMLData parent, XMLData child)
    {
        for (String attrName : new String[]{LOGS_ROLLING_TIME_ATTR, LOGS_ROLLING_SIZE_ATTR, LOGS_ROLLOUT_ON_START_ATTR, TIME_LAPSE_ATTR})
        {
            String childValue = child.getAttributeStringValue(attrName, null);
            if (childValue == null)
            {
                String parentValue = parent.getAttributeStringValue(attrName, null);
                if (parentValue != null)
                    child.setAttributeValue(attrName, parentValue);
            }
        }
    }

    private LogFacilityManager() {}
}
