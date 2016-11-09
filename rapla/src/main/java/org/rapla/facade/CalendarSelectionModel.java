package org.rapla.facade;

import java.util.Collection;
import java.util.Date;

import org.rapla.components.util.TimeInterval;
import org.rapla.entities.domain.Allocatable;
import org.rapla.entities.dynamictype.ClassificationFilter;
import org.rapla.framework.RaplaException;

public interface CalendarSelectionModel extends CalendarModel{
    String getTitle();

	void setTitle(String title);

	void setViewId(String viewId);

	String getViewId();
		
	void setSelectedObjects(Collection<? extends Object> selectedObjects);

    void setOption( String name, String string );

    /** If show only own reservations is selected. Thats if the current user is selected with select User*/
    boolean isOnlyCurrentUserSelected();

    void setReservationFilter(ClassificationFilter[] array);

    void setAllocatableFilter(ClassificationFilter[] filters);

    void resetExports();
    void save(final String filename) throws RaplaException;

    void load(final String filename) throws RaplaException, CalendarNotFoundExeption;
    
    CalendarSelectionModel clone();
	
	void setMarkedIntervals(Collection<TimeInterval> timeIntervals,  boolean timeEnabled);
	/** calls setMarkedIntervals with a single interval from start to end*/
	void markInterval(Date start, Date end);

	void setMarkedAllocatables(Collection<Allocatable> allocatable);

    boolean isMarkedIntervalTimeEnabled();

}