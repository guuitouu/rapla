package org.rapla.plugin.export2ical.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

import org.rapla.inject.DefaultImplementation;
import org.rapla.inject.InjectionContext;
import org.rapla.plugin.export2ical.ICalTimezones;
import org.rapla.server.TimeZoneConverter;

import net.fortuna.ical4j.model.TimeZone;

@DefaultImplementation(context = InjectionContext.server, of = ICalTimezones.class)
public class RaplaICalTimezones implements ICalTimezones
{

    List<String> availableIDs;
    @Inject
    TimeZoneConverter converter;

    @Inject
    public RaplaICalTimezones()
    {
        availableIDs = new ArrayList<String>(Arrays.asList(TimeZone.getAvailableIDs()));
        Collections.sort(availableIDs, String.CASE_INSENSITIVE_ORDER);
    }

    public List<String> getICalTimezones()
    {
        List<String> result = new ArrayList<String>();
        for (String id : availableIDs)
        {
            result.add(id);
        }
        return result;
    }

    //public static final String TIMEZONE = "timezone";

    public String getDefaultTimezone()
    {
        String id = converter.getImportExportTimeZone().getID();
        return id;
    }

}
