/*--------------------------------------------------------------------------*
 | Copyright (C) 2014 Christopher Kohlhaas                                  |
 |                                                                          |
 | This program is free software; you can redistribute it and/or modify     |
 | it under the terms of the GNU General Public License as published by the |
 | Free Software Foundation. A copy of the license has been included with   |
 | these distribution in the COPYING file, if not go to www.fsf.org         |
 |                                                                          |
 | As a special exception, you are granted the permissions to link this     |
 | program with every library, which license fulfills the Open Source       |
 | Definition as published by the Open Source Initiative (OSI).             |
 *--------------------------------------------------------------------------*/
package org.rapla.entities.domain.internal;

import java.util.Date;
import java.util.Locale;

import org.rapla.components.util.DateTools;
import org.rapla.entities.domain.Period;

public class PeriodImpl implements Period
{
    private final static long WEEK_MILLIS= DateTools.MILLISECONDS_PER_WEEK;
    String name;
    Date start;
    Date end;
    String id;

    public PeriodImpl() {
    }

    public PeriodImpl(String name,Date start, Date end, String id) {
        this.name = name;
    	this.start = start;
        this.end = end;
        this.id = id;
    }

    @Override
    public Class<Period> getTypeClass()
    {
        return Period.class;
    }

    public Date getStart() {
        return start;
    }

    public Date getEnd() {
        return end;
    }

    public int getWeeks()
    {
    	if ( end == null || start == null)
    	{
    		return -1;
    	}
    	long diff= end.getTime()-start.getTime();
        return (int)(((diff-1)/WEEK_MILLIS )+ 1);
    }

    public String getName(Locale locale) {
        return name;
    }

    public String getName() {
        return name;
    }

    public boolean contains(Date date) {
        if ( date == null)
        {
            return false;
        }
        return ((end == null || date.before(end))&& (start == null || !date.before(start)));
    }

    public String toString() {
        return getName() + " " + getStart() + " - " + getEnd();
    }

    @Override public boolean equals(Object obj)
    {
        if (!( obj instanceof PeriodImpl))
        {
            return false;
        }
        return id.equals(((PeriodImpl)obj).id);
    }

    @Override public int hashCode()
    {
        return id.hashCode();
    }

    /*
    public int compareTo_(Date date) {
        final Date end2 = getEnd();
        int result = end2.compareTo(date);
        if (result == 0)
            return 1;
        else
            return result;
    }
    */

    public int compareTo(Period period) {
        final Date start1 = getStart();
        final Date start2 = period.getStart();
        if ( start1 == null || start2 == null)
        {
            if ( start1 != null)
            {
                return 1;              
            }
            if ( start2 != null)
            {
                return -1;              
            }
        } else {
            int result = start1.compareTo(start2);
            if (result != 0) return result;
        }

        if (equals(period))
            return 0;

        return (hashCode() < period.hashCode()) ? -1 : 1;
    }


    public PeriodImpl clone()
    {
    	return new PeriodImpl(name, start, end, id);
    }

	public void setStart(Date start) {
		this.start = start;
	}

	public void setEnd(Date end) {
		this.end = end;
	}

    public String getId()
    {
        return id;
    }
}




